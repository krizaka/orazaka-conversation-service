package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.core.application.engine.GraphEngine;
import com.krizaka.orazaka.core.domain.model.OllamaModel;
import com.krizaka.orazaka.core.domain.model.OperationGraph;
import com.krizaka.orazaka.core.domain.ports.outbound.InfrastructureStatusProvider;
import com.krizaka.orazaka.core.domain.ports.outbound.ModelCatalogProvider;
import com.krizaka.orazaka.core.infrastructure.config.CoreProperties;
import java.net.InetAddress;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the {@code status} resource: live infrastructure availability, sovereign
 * compliance health, and the compiled operation graph.
 *
 * <p>Consolidates the former InfrastructureStatus, Compliance and OperationGraph controllers.
 * {@code /health} is the public sovereign readiness probe (permitted via SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

  private static final Logger log = LoggerFactory.getLogger(StatusController.class);

  private final InfrastructureStatusProvider prober;
  private final DataSource dataSource;
  private final ModelCatalogProvider modelCatalogProvider;
  private final CoreProperties coreProperties;
  private final GraphEngine graphEngine;
  private final String ollamaBaseUrl;

  public StatusController(
      InfrastructureStatusProvider prober,
      DataSource dataSource,
      ModelCatalogProvider modelCatalogProvider,
      CoreProperties coreProperties,
      GraphEngine graphEngine,
      @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl) {
    this.prober = Objects.requireNonNull(prober, "InfrastructureStatusProvider must not be null");
    this.dataSource = Objects.requireNonNull(dataSource, "DataSource must not be null");
    this.modelCatalogProvider =
        Objects.requireNonNull(modelCatalogProvider, "ModelCatalogProvider must not be null");
    this.coreProperties = Objects.requireNonNull(coreProperties, "CoreProperties must not be null");
    this.graphEngine = Objects.requireNonNull(graphEngine, "GraphEngine must not be null");
    this.ollamaBaseUrl = Objects.requireNonNull(ollamaBaseUrl, "Ollama base URL must not be null");
  }

  /** Returns the current online/offline snapshot of the local inference engines. */
  @GetMapping("/infrastructure")
  public ResponseEntity<@NotNull InfrastructureStatusResponse> infrastructure() {
    return ResponseEntity.ok(
        new InfrastructureStatusResponse(
            prober.isVideoEngineOnline(), prober.isImageEngineOnline()));
  }

  /** Compiles and outputs the operations graph. */
  @GetMapping("/graph")
  public ResponseEntity<@NotNull OperationGraph> getGraph() {
    return ResponseEntity.ok(graphEngine.compileGraph());
  }

  /** Returns sovereign readiness health details (public probe). */
  @GetMapping("/health")
  public ResponseEntity<@NotNull ComplianceResponse> getComplianceHealth() {
    boolean dbConnected = checkDbConnection();
    boolean pgVectorInstalled = dbConnected && checkPgVectorInstalled();

    OllamaStatus ollamaStatus = checkOllamaStatus();
    boolean ollamaConnected = ollamaStatus.connected();
    List<String> installedModels = ollamaStatus.models();

    String host = resolveHost();
    boolean isLocalOrPrivate = isHostLocalOrPrivate(host);

    String defaultProvider = coreProperties.defaultProvider();
    boolean isOllamaDefault = "ollama".equalsIgnoreCase(defaultProvider);
    boolean sovereignReady =
        dbConnected && pgVectorInstalled && ollamaConnected && isOllamaDefault && isLocalOrPrivate;

    String status = sovereignReady ? "Sovereign-Ready" : "Non-Compliant";
    String message =
        buildMessage(
            sovereignReady,
            dbConnected,
            pgVectorInstalled,
            ollamaConnected,
            isOllamaDefault,
            isLocalOrPrivate,
            defaultProvider,
            host);

    ComplianceResponse response =
        new ComplianceResponse(
            status,
            dbConnected,
            pgVectorInstalled,
            ollamaConnected,
            installedModels,
            defaultProvider,
            ollamaBaseUrl,
            message);

    return ResponseEntity.ok(response);
  }

  private boolean checkDbConnection() {
    try (Connection conn = dataSource.getConnection()) {
      return conn != null;
    } catch (Exception e) {
      log.error("Compliance healthcheck: Database connection failed", e);
      return false;
    }
  }

  private boolean checkPgVectorInstalled() {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT extname FROM pg_extension WHERE extname = 'vector'")) {
      return rs.next();
    } catch (Exception e) {
      log.error("Compliance healthcheck: PGVector validation failed", e);
      return false;
    }
  }

  private OllamaStatus checkOllamaStatus() {
    try {
      var catalogOpt = modelCatalogProvider.getCatalog();
      if (catalogOpt.isPresent()) {
        List<String> models = catalogOpt.get().models().stream().map(OllamaModel::name).toList();
        return new OllamaStatus(true, models);
      }
    } catch (Exception e) {
      log.error("Compliance healthcheck: Ollama validation failed", e);
    }
    return new OllamaStatus(false, List.of());
  }

  private String resolveHost() {
    try {
      URI uri = new URI(ollamaBaseUrl);
      String host = uri.getHost();
      if (host == null) {
        host = ollamaBaseUrl.replace("http://", "").replace("https://", "").split(":")[0];
      }
      return host;
    } catch (Exception e) {
      log.warn("Compliance healthcheck: failed to parse host from base URL", e);
      return "unknown";
    }
  }

  private boolean isHostLocalOrPrivate(String host) {
    try {
      if ("host.docker.internal".equalsIgnoreCase(host)
          || "ollama".equalsIgnoreCase(host)
          || "localhost".equalsIgnoreCase(host)) {
        return true;
      }
      InetAddress address = InetAddress.getByName(host);
      if (address.isLoopbackAddress()
          || address.isSiteLocalAddress()
          || address.isLinkLocalAddress()) {
        return true;
      }
      byte[] ip = address.getAddress();
      if (ip.length == 4) {
        int first = ip[0] & 0xFF;
        int second = ip[1] & 0xFF;
        if (first == 10
            || (first == 172 && (second >= 16 && second <= 31))
            || (first == 192 && second == 168)) {
          return true;
        }
      }
    } catch (Exception e) {
      log.warn("Compliance healthcheck: host resolution warning for " + host, e);
    }
    return false;
  }

  private String buildMessage(
      boolean sovereignReady,
      boolean dbConnected,
      boolean pgVectorInstalled,
      boolean ollamaConnected,
      boolean isOllamaDefault,
      boolean isLocalOrPrivate,
      String defaultProvider,
      String host) {
    if (sovereignReady) {
      return "Local PGVector database is connected with the 'vector' extension active, the local"
          + " LLM client (Ollama) is responsive at a private/local network address ("
          + host
          + "), and the default provider is local. AI pipeline is strictly sovereign (on-premise).";
    }

    StringBuilder errorMsg = new StringBuilder("Compliance check failed: ");
    if (!dbConnected) {
      errorMsg.append("Database offline; ");
    } else if (!pgVectorInstalled) {
      errorMsg.append("PGVector extension not installed; ");
    }
    if (!ollamaConnected) {
      errorMsg.append("Ollama service offline; ");
    }
    if (!isOllamaDefault) {
      errorMsg
          .append("Active AI provider is set to non-local value: '")
          .append(defaultProvider)
          .append("'; ");
    }
    if (!isLocalOrPrivate) {
      errorMsg
          .append("Ollama client base URL host '")
          .append(host)
          .append("' resolved to a public network address (Sovereignty compromise hazard); ");
    }
    return errorMsg.toString().trim();
  }

  /** Live engine availability snapshot. */
  public record InfrastructureStatusResponse(boolean video, boolean image) {}

  private record OllamaStatus(boolean connected, List<String> models) {}

  /** DTO representing the compliance healthcheck response. */
  public record ComplianceResponse(
      String status,
      boolean pgVectorConnected,
      boolean pgVectorExtensionInstalled,
      boolean ollamaConnected,
      List<String> ollamaModels,
      String defaultProvider,
      String ollamaBaseUrl,
      String message) {
    /** Compact constructor validating fields. */
    public ComplianceResponse {
      Objects.requireNonNull(status, "status must not be null");
      Objects.requireNonNull(defaultProvider, "defaultProvider must not be null");
      Objects.requireNonNull(ollamaBaseUrl, "ollamaBaseUrl must not be null");
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(ollamaModels, "ollamaModels must not be null");
    }
  }
}
