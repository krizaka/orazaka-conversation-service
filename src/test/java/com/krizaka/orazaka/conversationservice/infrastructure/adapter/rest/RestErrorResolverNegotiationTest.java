package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.krizaka.orazaka.core.application.pipeline.PipelineDisabledException;
import com.krizaka.orazaka.studio.domain.exception.TurnConsentRequiredException;
import com.krizaka.web.KrizakaWebProperties;
import com.krizaka.web.problem.ProblemDetailsAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A refusal answers in JSON whatever the caller said it accepts.
 *
 * <p>A streaming client (the voice turn, the chat stream) asks for {@code text/event-stream}. A
 * refusal raised before the stream opens (451 consent, 402 credits, 503 engine off) is a JSON body:
 * with no content type of its own, Spring found nothing producible for that {@code Accept} and the
 * refusal itself failed — a 500, and the caller read « unavailable » where the answer was « agree
 * to version 2.1 » (found live on dev).
 */
class RestErrorResolverNegotiationTest {

  @RestController
  static class Refusing {
    @PostMapping("/consent")
    void consent() {
      throw new TurnConsentRequiredException("2.1", "Ce Studio ne garde rien.");
    }

    @PostMapping("/off")
    void off() {
      throw new PipelineDisabledException();
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Refusing())
          .setControllerAdvice(
              new RestErrorResolver(new ProblemDetailsAdvice(new KrizakaWebProperties(null, null))))
          .build();

  @Test
  @DisplayName("a consent refusal is a 451 JSON body for a caller that accepts text/event-stream")
  void consentIsJsonForAnEventStreamCaller() throws Exception {
    mvc.perform(post("/consent").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().is(451))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value("consent_required"))
        .andExpect(jsonPath("$.consentVersion").value("2.1"));
  }

  @Test
  @DisplayName(
      "an engine switched off is a 503 JSON body for a caller that accepts text/event-stream")
  void engineOffIsJsonForAnEventStreamCaller() throws Exception {
    mvc.perform(post("/off").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value("engine_disabled"));
  }
}
