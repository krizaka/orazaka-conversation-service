package com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence;

import com.krizaka.orazaka.conversationservice.domain.port.ProductCheckAudit;
import com.krizaka.orazaka.core.domain.model.checks.CheckPurpose;
import com.krizaka.orazaka.core.domain.ports.outbound.CheckAuditLog;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The checks' audit in {@code external_check_audit}: appended, never rewritten (the table's trigger
 * refuses an update). It holds no personal data of the subject: the engine names the subject by a
 * keyed HMAC of its opaque id. A failure to write is not swallowed, so an unaudited call is never
 * reported as done.
 */
@Component
public class JdbcProductCheckAuditAdapter implements ProductCheckAudit {

  private static final String APPEND =
      "INSERT INTO external_check_audit (at, tenant_id, actor_id, run_id, provider, purpose,"
          + " key_source, outcome, refusal, subject_ref, duration_ms, provider_ref,"
          + " provider_cost_micros) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SUBMITTED =
      "SELECT provider, purpose, run_id FROM external_check_audit"
          + " WHERE tenant_id = ? AND provider_ref = ? ORDER BY at DESC, id DESC LIMIT 1";

  private final JdbcTemplate jdbcTemplate;

  public JdbcProductCheckAuditAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  @Override
  public CheckAuditLog forTenant(String tenant) {
    Objects.requireNonNull(tenant, "tenant cannot be null");
    return line ->
        jdbcTemplate.update(
            APPEND,
            Timestamp.from(line.at()),
            tenant,
            line.actorId(),
            line.runId(),
            line.provider(),
            line.purpose().name(),
            line.keySource() == null ? null : line.keySource().name(),
            line.outcome().name(),
            line.refusal() == null ? null : line.refusal().name(),
            line.subjectRef(),
            line.durationMs(),
            line.providerRef(),
            line.providerCostMicros());
  }

  @Override
  public Optional<Submitted> submitted(String tenant, String providerRef) {
    return jdbcTemplate
        .query(
            SUBMITTED,
            (rs, i) ->
                new Submitted(
                    rs.getString("provider"),
                    CheckPurpose.valueOf(rs.getString("purpose")),
                    rs.getObject("run_id", UUID.class)),
            tenant,
            providerRef)
        .stream()
        .findFirst();
  }
}
