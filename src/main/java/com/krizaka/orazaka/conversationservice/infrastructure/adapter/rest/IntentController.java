package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.business.api.AgentPayload;
import com.krizaka.orazaka.business.api.Capability;
import com.krizaka.orazaka.business.api.ChatPayload;
import com.krizaka.orazaka.business.api.ExecutionMode;
import com.krizaka.orazaka.business.api.ImagePayload;
import com.krizaka.orazaka.business.api.Intention;
import com.krizaka.orazaka.business.api.IntentionContext;
import com.krizaka.orazaka.business.api.IntentionType;
import com.krizaka.orazaka.business.api.Payload;
import com.krizaka.orazaka.business.api.StudioPayload;
import com.krizaka.orazaka.business.api.UseCaseDispatcher;
import com.krizaka.orazaka.business.api.UseCaseResolutionException;
import com.krizaka.orazaka.conversationservice.application.service.GateService;
import com.krizaka.orazaka.conversationservice.domain.model.gate.AuthGate;
import com.krizaka.orazaka.conversationservice.domain.model.gate.StaticGate;
import com.krizaka.orazaka.conversationservice.domain.model.intent.IntentEnvelope;
import com.krizaka.orazaka.conversationservice.domain.model.intent.IntentToken;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.IntentionRequest;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.users.domain.model.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for the {@code intent} resource.
 *
 * <p>Consolidates the Sovereign Intent Router (M2M, Level-0 gate evaluation at {@code /route}) and
 * the App-Factory ingress (authenticated intention dispatch at the collection root). The M2M {@code
 * /route} endpoint is permitted via SecurityConfig; the dispatch endpoint is authenticated.
 */
@RestController
@RequestMapping("/api/v1/intent")
public class IntentController {

  private final GateService gateEvaluator;
  private final UseCaseDispatcher dispatcher;

  public IntentController(GateService gateEvaluator, UseCaseDispatcher dispatcher) {
    this.gateEvaluator = Objects.requireNonNull(gateEvaluator, "GateService must not be null");
    this.dispatcher = Objects.requireNonNull(dispatcher, "UseCaseDispatcher must not be null");
  }

  /** M2M Level-0 gate routing: verifies the envelope and returns a signed intent token. */
  @PostMapping("/route")
  public ResponseEntity<IntentToken> routeIntent(@RequestBody IntentEnvelope envelope) {
    StaticGate gate = resolveGate(envelope);
    IntentToken token = gateEvaluator.evaluate(gate);
    return ResponseEntity.ok(token);
  }

  /** Dispatches a synchronous user intention through the App Factory and returns the result. */
  @PostMapping
  public ResponseEntity<Object> submit(
      @RequestBody IntentionRequest request, @AuthenticationPrincipal User user) {
    Intention intention = toIntention(request, user);
    try {
      Object result = dispatcher.dispatch(intention);
      return ResponseEntity.ok(result);
    } catch (UseCaseResolutionException e) {
      boolean denied = e.getMessage() != null && e.getMessage().contains("ERR-403");
      throw new ResponseStatusException(
          denied ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static StaticGate resolveGate(IntentEnvelope envelope) {
    Object scopesRaw = envelope.metadata().get("scopes");
    List<String> scopes =
        scopesRaw instanceof List<?> list
            ? list.stream().map(Object::toString).toList()
            : List.of();
    boolean verified = Boolean.TRUE.equals(envelope.metadata().get("verified"));
    return new AuthGate(envelope.userId(), scopes, verified);
  }

  private static Intention toIntention(IntentionRequest request, User user) {
    Capability capability;
    try {
      capability = Capability.valueOf(request.capability().trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unknown capability: " + request.capability());
    }

    Payload payload =
        switch (capability) {
          case CHAT -> new ChatPayload(request.prompt(), List.of());
          case IMAGE -> new ImagePayload(request.prompt(), request.size());
          case AGENT ->
              new AgentPayload(
                  request.goal() != null ? request.goal() : request.prompt(), Map.of());
            // A Studio run is a first-class Intention (ADR-034 §9.2): the same marketplace the web
            // client uses is reachable from here, the CLI and the agent loop alike.
          case STUDIO ->
              new StudioPayload(UUID.fromString(request.installationId()), request.inputs());
          case AUDIO, VIDEO, ADMIN ->
              throw new ResponseStatusException(
                  HttpStatus.BAD_REQUEST,
                  "capability not yet wired through the App Factory: " + capability);
        };

    IntentionType type =
        (capability == Capability.CHAT) ? IntentionType.QUERY : IntentionType.COMMAND;
    IntentionContext context =
        new IntentionContext(
            request.sessionId(),
            user != null ? user.id().toString() : null,
            user != null ? user.authorities() : Set.of(),
            // The user's own namespace, as every other Context builder does (ADR-064).
            user != null ? Context.userPreferences(user.preferences()) : Map.of());

    return new Intention(
        null, type, ExecutionMode.SYNC, capability, request.goal(), payload, context);
  }
}
