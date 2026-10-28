package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.krizaka.orazaka.conversationservice.application.service.AgentTurnService;
import com.krizaka.orazaka.conversationservice.application.service.ContextService;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnRequest;
import com.krizaka.orazaka.core.domain.model.chat.AgentTurnResult;
import com.krizaka.orazaka.core.domain.model.chat.TokenUsage;
import com.krizaka.orazaka.core.domain.model.chat.ToolCall;
import com.krizaka.orazaka.core.domain.model.security.AccessDeniedException;
import com.krizaka.orazaka.core.domain.ports.inbound.AgentTurns;
import com.krizaka.users.domain.model.User;
import com.krizaka.users.domain.port.UserDirectoryClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code POST /internal/v1/agent-turns} (ADR-093 §4): the wire the Studio service speaks, the actor
 * hydrated from the directory, and the refusals as a {@code status} in the body.
 */
class AgentTurnsControllerTest {

  private static final String ACTOR_ID = "00000000-0000-0000-0000-000000000042";
  private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
  private static final String BODY =
      """
      {"runId":"%s","actorId":"%s","featureKey":"agent.code.issue-to-pr","system":"You fix issues.",
       "messages":[
         {"role":"user","content":"Fix #12"},
         {"role":"assistant","content":"","calls":[{"id":"c1","name":"read_file","argumentsJson":"{\\"path\\":\\"a\\"}"}]},
         {"role":"tool","content":"hello","toolCallId":"c1"}],
       "tools":[{"name":"read_file","description":"Reads a file","inputSchema":"{}"}]}"""
          .formatted(RUN_ID, ACTOR_ID);

  private AgentTurns turns;
  private UserDirectoryClient users;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    turns = mock(AgentTurns.class);
    users = mock(UserDirectoryClient.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentTurnsController(
                    new AgentTurnService(turns, new ContextService(users), users)))
            .build();
  }

  private static User actor(boolean enabled) {
    return new User(
        UUID.fromString(ACTOR_ID),
        "eric",
        "eric@example.com",
        enabled,
        Set.of("ROLE_USER"),
        Map.of(),
        List.of());
  }

  @Test
  @DisplayName("the turn reaches the engine with the hydrated actor and the run as conversation")
  void turnCarriesTheActorAndTheConversation() throws Exception {
    when(users.getUser(ACTOR_ID)).thenReturn(actor(true));
    when(turns.turn(any()))
        .thenReturn(
            new AgentTurnResult(
                "I will read it",
                List.of(new ToolCall("c2", "read_file", "{\"path\":\"b\"}")),
                TokenUsage.reported(10, 4, 14)));

    mvc.perform(
            post("/internal/v1/agent-turns").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("I will read it"))
        .andExpect(jsonPath("$.toolCalls[0].id").value("c2"))
        .andExpect(jsonPath("$.toolCalls[0].name").value("read_file"))
        .andExpect(jsonPath("$.toolCalls[0].argumentsJson").value("{\"path\":\"b\"}"))
        .andExpect(jsonPath("$.usage.promptTokens").value(10))
        .andExpect(jsonPath("$.usage.completionTokens").value(4))
        .andExpect(jsonPath("$.usage.creditsMicro").value(0));

    ArgumentCaptor<AgentTurnRequest> sent = ArgumentCaptor.forClass(AgentTurnRequest.class);
    org.mockito.Mockito.verify(turns).turn(sent.capture());
    AgentTurnRequest request = sent.getValue();
    assertThat(request.featureKey()).isEqualTo("agent.code.issue-to-pr");
    assertThat(request.system()).isEqualTo("You fix issues.");
    assertThat(request.context().userId()).isEqualTo(ACTOR_ID);
    assertThat(request.context().conversationId()).isEqualTo(RUN_ID);
    assertThat(request.tools()).extracting("name").containsExactly("read_file");
    assertThat(request.messages()).extracting("role").containsExactly("user", "assistant", "tool");
    assertThat(request.messages().get(1).calls()).extracting("id").containsExactly("c1");
    assertThat(request.messages().get(2).toolCallId()).isEqualTo("c1");
  }

  @Test
  @DisplayName("an unreported usage is 0, never the engine's -1")
  void unreportedUsageIsZero() throws Exception {
    when(users.getUser(ACTOR_ID)).thenReturn(actor(true));
    when(turns.turn(any())).thenReturn(new AgentTurnResult("ok", List.of(), TokenUsage.none()));

    mvc.perform(
            post("/internal/v1/agent-turns").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.usage.promptTokens").value(0))
        .andExpect(jsonPath("$.toolCalls").isEmpty());
  }

  @Test
  @DisplayName("a disabled actor: 403 actor_disabled, the engine is never asked")
  void disabledActorIsRefused() throws Exception {
    when(users.getUser(ACTOR_ID)).thenReturn(actor(false));

    mvc.perform(
            post("/internal/v1/agent-turns").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value("actor_disabled"));
    verifyNoInteractions(turns);
  }

  @Test
  @DisplayName("an unknown actor: 404 actor_unknown")
  void unknownActorIsRefused() throws Exception {
    when(users.getUser(ACTOR_ID)).thenThrow(new IllegalArgumentException("no such user"));

    mvc.perform(
            post("/internal/v1/agent-turns").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("actor_unknown"));
    verifyNoInteractions(turns);
  }

  @Test
  @DisplayName("the engine refusing the actor: 403 access_denied, no reason in the answer")
  void engineRefusalIsAStatus() throws Exception {
    when(users.getUser(ACTOR_ID)).thenReturn(actor(true));
    when(turns.turn(any()))
        .thenThrow(new AccessDeniedException("tools", AccessDeniedException.Reason.NOT_OFFERED));

    mvc.perform(
            post("/internal/v1/agent-turns").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value("access_denied"))
        .andExpect(jsonPath("$.message").doesNotExist());
  }

  @Test
  @DisplayName("a body without actor, run or feature key: 400 invalid_turn")
  void malformedBodyIsRefused() throws Exception {
    mvc.perform(
            post("/internal/v1/agent-turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"runId\":\"" + RUN_ID + "\",\"featureKey\":\"agent.code.issue-to-pr\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("invalid_turn"));
    verifyNoInteractions(turns);
  }
}
