package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.core.domain.event.ToolExecutedEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tool-call event is stored in the audit log: who, feature, tool, duration, outcome (#169). */
class ToolCallAuditSinkTest {

  private final List<OperatorAuditStore.Line> written = new ArrayList<>();
  private OperatorAuditStore store =
      new OperatorAuditStore() {
        @Override
        public void append(Line line) {
          written.add(line);
        }

        @Override
        public List<Stored> recent(int limit) {
          return List.of();
        }
      };

  @Test
  void aToolRunLeavesOneLineWithWhoFeatureToolDurationAndOutcome() {
    new ToolCallAuditSink(store)
        .onToolExecuted(
            new ToolExecutedEvent(
                "conv-1",
                "searchKnowledge",
                "call-1",
                "user-7",
                "agent.code.issue-to-pr",
                412L,
                "ok"));

    assertThat(written).hasSize(1);
    var line = written.getFirst();
    assertThat(line.actorId()).isEqualTo("user-7");
    assertThat(line.action()).isEqualTo("tool-call searchKnowledge");
    assertThat(line.targetId()).isEqualTo("agent.code.issue-to-pr");
    assertThat(line.durationMs()).isEqualTo(412L);
    assertThat(line.outcome()).isEqualTo("ok");
  }

  @Test
  void theLineCarriesNoPromptNorArgumentsNorConversationContent() {
    new ToolCallAuditSink(store)
        .onToolExecuted(
            new ToolExecutedEvent("conv-1", "lookup", "call-1", "user-7", null, 5L, "ok"));

    assertThat(written.getFirst().toString()).doesNotContain("conv-1").doesNotContain("call-1");
    assertThat(written.getFirst().targetId()).isNull();
  }

  @Test
  void anEventWithoutAUserIsStoredAsTheEngineNotAsNobody() {
    new ToolCallAuditSink(store).onToolExecuted(new ToolExecutedEvent("conv-1", "lookup", "c"));

    assertThat(written.getFirst().actorId()).isNull();
    assertThat(written.getFirst().actorLabel()).isEqualTo("engine");
  }

  @Test
  void aStoreThatFailsNeverFailsTheTurn() {
    store =
        new OperatorAuditStore() {
          @Override
          public void append(Line line) {
            throw new IllegalStateException("db down");
          }

          @Override
          public List<Stored> recent(int limit) {
            return List.of();
          }
        };

    new ToolCallAuditSink(store).onToolExecuted(new ToolExecutedEvent("conv-1", "lookup", "c"));
    // no exception reached the caller
  }
}
