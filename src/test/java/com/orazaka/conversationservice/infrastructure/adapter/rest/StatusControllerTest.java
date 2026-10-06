package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.orazaka.core.application.engine.GraphEngine;
import com.orazaka.core.domain.model.OllamaCatalog;
import com.orazaka.core.domain.model.OllamaModel;
import com.orazaka.core.domain.model.OperationGraph;
import com.orazaka.core.domain.ports.outbound.InfrastructureStatusProvider;
import com.orazaka.core.domain.ports.outbound.ModelCatalogProvider;
import com.orazaka.core.infrastructure.config.CoreProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link StatusController} (infrastructure, graph, compliance health). */
class StatusControllerTest {

  private static final String LOCAL_URL = "http://localhost:11434";

  private InfrastructureStatusProvider prober;
  private DataSource dataSource;
  private Connection connection;
  private Statement statement;
  private ResultSet resultSet;
  private ModelCatalogProvider modelCatalogProvider;
  private CoreProperties coreProperties;
  private GraphEngine graphEngine;
  private StatusController controller;

  private StatusController newController(String ollamaUrl) {
    return new StatusController(
        prober, dataSource, modelCatalogProvider, coreProperties, graphEngine, ollamaUrl);
  }

  @BeforeEach
  void setUp() throws Exception {
    prober = mock(InfrastructureStatusProvider.class);
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    statement = mock(Statement.class);
    resultSet = mock(ResultSet.class);
    modelCatalogProvider = mock(ModelCatalogProvider.class);
    coreProperties = mock(CoreProperties.class);
    graphEngine = mock(GraphEngine.class);

    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(resultSet);

    controller = newController(LOCAL_URL);
  }

  @Test
  @DisplayName("infrastructure() reflects the prober snapshot")
  void infrastructure_reflectsProber() {
    when(prober.isVideoEngineOnline()).thenReturn(true);
    when(prober.isImageEngineOnline()).thenReturn(false);
    var response = controller.infrastructure();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().video()).isTrue();
    assertThat(response.getBody().image()).isFalse();
  }

  @Test
  @DisplayName("getGraph() returns the compiled operation graph")
  void getGraph_returnsCompiledGraph() {
    OperationGraph graph = mock(OperationGraph.class);
    when(graphEngine.compileGraph()).thenReturn(graph);
    var response = controller.getGraph();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(graph);
  }

  @Test
  @DisplayName("Constructor throws NullPointerException on null datasource")
  void constructor_nullDataSource_throws() {
    assertThatThrownBy(
            () ->
                new StatusController(
                    prober, null, modelCatalogProvider, coreProperties, graphEngine, LOCAL_URL))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("DataSource");
  }

  @Test
  @DisplayName("Constructor throws NullPointerException on null ollamaBaseUrl")
  void constructor_nullOllamaBaseUrl_throws() {
    assertThatThrownBy(() -> newController(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Ollama base URL");
  }

  @Test
  @DisplayName("Sovereign-Ready when DB+pgvector active, Ollama up, default provider ollama")
  void health_sovereignReady_success() throws Exception {
    when(resultSet.next()).thenReturn(true);
    var catalog = new OllamaCatalog(List.of(new OllamaModel("llama3.2:3b", "llama3.2:3b", "sha")));
    when(modelCatalogProvider.getCatalog()).thenReturn(Optional.of(catalog));
    when(coreProperties.defaultProvider()).thenReturn("ollama");

    ResponseEntity<StatusController.ComplianceResponse> response = controller.getComplianceHealth();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo("Sovereign-Ready");
    assertThat(body.pgVectorConnected()).isTrue();
    assertThat(body.ollamaConnected()).isTrue();
    assertThat(body.ollamaModels()).containsExactly("llama3.2:3b");
    assertThat(body.message()).contains("strictly sovereign");
  }

  @Test
  @DisplayName("Non-Compliant when using public IP address (e.g. 8.8.8.8)")
  void health_publicIpAddress_nonCompliant() throws Exception {
    StatusController publicController = newController("http://8.8.8.8:11434");
    when(resultSet.next()).thenReturn(true);
    var catalog = new OllamaCatalog(List.of(new OllamaModel("llama3.2", "llama3.2", "sha")));
    when(modelCatalogProvider.getCatalog()).thenReturn(Optional.of(catalog));
    when(coreProperties.defaultProvider()).thenReturn("ollama");

    var response = publicController.getComplianceHealth();

    assertThat(response.getBody().status()).isEqualTo("Non-Compliant");
    assertThat(response.getBody().message()).contains("resolved to a public network address");
  }

  @Test
  @DisplayName("Non-Compliant when database connection fails")
  void health_databaseOffline_nonCompliant() throws Exception {
    when(dataSource.getConnection()).thenThrow(new RuntimeException("DB offline"));
    var catalog = new OllamaCatalog(List.of(new OllamaModel("llama3.2:3b", "llama3.2:3b", "sha")));
    when(modelCatalogProvider.getCatalog()).thenReturn(Optional.of(catalog));
    when(coreProperties.defaultProvider()).thenReturn("ollama");

    var body = controller.getComplianceHealth().getBody();

    assertThat(body.status()).isEqualTo("Non-Compliant");
    assertThat(body.pgVectorConnected()).isFalse();
    assertThat(body.message()).contains("Database offline");
  }

  @Test
  @DisplayName("Non-Compliant when Ollama is offline")
  void health_ollamaOffline_nonCompliant() throws Exception {
    when(resultSet.next()).thenReturn(true);
    when(modelCatalogProvider.getCatalog()).thenReturn(Optional.empty());
    when(coreProperties.defaultProvider()).thenReturn("ollama");

    var body = controller.getComplianceHealth().getBody();

    assertThat(body.status()).isEqualTo("Non-Compliant");
    assertThat(body.ollamaConnected()).isFalse();
    assertThat(body.message()).contains("Ollama service offline");
  }

  @Test
  @DisplayName("Non-Compliant when default provider is non-local (e.g. openai)")
  void health_nonLocalProvider_nonCompliant() throws Exception {
    when(resultSet.next()).thenReturn(true);
    when(modelCatalogProvider.getCatalog()).thenReturn(Optional.of(new OllamaCatalog(List.of())));
    when(coreProperties.defaultProvider()).thenReturn("openai");

    var body = controller.getComplianceHealth().getBody();

    assertThat(body.status()).isEqualTo("Non-Compliant");
    assertThat(body.message()).contains("Active AI provider is set to non-local value: 'openai'");
  }
}
