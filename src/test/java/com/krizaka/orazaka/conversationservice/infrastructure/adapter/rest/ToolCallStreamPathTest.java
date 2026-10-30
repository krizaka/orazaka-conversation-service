package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.orazaka.adapter.springai.CoreServicesAutoConfiguration;
import com.krizaka.orazaka.adapter.springai.chat.SpringAiChatInvocation;
import com.krizaka.orazaka.adapter.springai.client.ChatGeneratorClientImpl;
import com.krizaka.orazaka.adapter.springai.prompt.SpringAiPromptAssembly;
import com.krizaka.orazaka.adapter.springai.routing.DynamicModelRouter;
import com.krizaka.orazaka.adapter.springai.routing.RoutedChatModel;
import com.krizaka.orazaka.adapter.springai.routing.UniversalProxyChatProvider;
import com.krizaka.orazaka.adapter.springai.tool.SpringAiToolOptions;
import com.krizaka.orazaka.adapter.springai.tool.SpringAiToolRegistry;
import com.krizaka.orazaka.adapter.springai.tool.SpringAiToolResolution;
import com.krizaka.orazaka.conversationservice.application.service.ContextService;
import com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto.ChatStreamRequest;
import com.krizaka.orazaka.conversationservice.infrastructure.config.JobsProperties;
import com.krizaka.orazaka.conversationservice.infrastructure.support.ChatStreamRegistry;
import com.krizaka.orazaka.conversationservice.infrastructure.support.SseStreamGateway;
import com.krizaka.orazaka.core.application.engine.Engine;
import com.krizaka.orazaka.core.application.pipeline.DynamicPipelineExecutor;
import com.krizaka.orazaka.core.application.routing.DynamicChatModelFactory;
import com.krizaka.orazaka.core.application.routing.SemanticRoutingEngine;
import com.krizaka.orazaka.core.domain.model.Authority;
import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.core.domain.model.InterceptorConfig;
import com.krizaka.orazaka.core.domain.model.ModelEndpoint;
import com.krizaka.orazaka.core.domain.ports.inbound.ChatSessionService;
import com.krizaka.orazaka.core.domain.ports.outbound.AccessAudit;
import com.krizaka.orazaka.core.domain.ports.outbound.ChatGeneratorClient;
import com.krizaka.orazaka.core.domain.ports.outbound.ChatInvocation;
import com.krizaka.orazaka.core.domain.ports.outbound.ImageGeneratorClient;
import com.krizaka.orazaka.core.domain.ports.outbound.KnowledgeService;
import com.krizaka.orazaka.core.domain.ports.outbound.ModelCatalogProvider;
import com.krizaka.orazaka.core.domain.ports.outbound.ModelEndpointResolver;
import com.krizaka.orazaka.core.domain.ports.outbound.PipelineConfigProvider;
import com.krizaka.orazaka.core.domain.ports.outbound.SemanticClassifierPort;
import com.krizaka.orazaka.core.domain.ports.outbound.SpeechSynthesis;
import com.krizaka.orazaka.core.domain.ports.outbound.ToolOptions;
import com.krizaka.orazaka.core.domain.ports.outbound.ToolRegistry;
import com.krizaka.orazaka.core.domain.ports.outbound.ToolResolution;
import com.krizaka.orazaka.core.domain.ports.outbound.UserCredentialsProvider;
import com.krizaka.orazaka.core.infrastructure.config.CoreProperties;
import com.krizaka.orazaka.core.infrastructure.config.SecurityProperties;
import com.krizaka.orazaka.interceptor.enrichment.EnrichmentAutoConfiguration;
import com.krizaka.orazaka.interceptor.tooling.ToolingAutoConfiguration;
import com.krizaka.orazaka.persistence.domain.ports.inbound.CatalogModelManager;
import com.krizaka.orazaka.persistence.domain.ports.inbound.ChatSessionPersistenceProvider;
import com.krizaka.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import com.krizaka.orazaka.persistence.domain.ports.inbound.RuntimeConfigProvider;
import com.krizaka.orazaka.tools.infrastructure.adapter.mcp.DefaultToolCatalogue;
import com.krizaka.users.domain.model.Persona;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * The free proof of the tool path (orazaka-ai-engine#169), the one that stands in front of any paid
 * run: a streamed chat turn, through the real {@link ChatController}, the real engine with its real
 * pipeline (the seeded interceptor rows, the RAG interceptor active), the real tool interceptor and
 * tool registry with the built-in {@code searchKnowledge}, the real Spring AI tool loop and the
 * real SSE relay, and only the model scripted: its first turn asks for {@code searchKnowledge}, its
 * second answers from the tool result.
 *
 * <p>Three things are asserted, each one a way the paid runs came back without a {@code tool-call}
 * event: the model is offered the tool, the client receives the {@code tool-call} event on the SSE
 * stream, and the tool's result is what the answer is made of.
 */
class ToolCallStreamPathTest {

  private static final String CONVERSATION = "conv-tool-path";
  private static final String KNOWLEDGE_HIT = "RENT-INCREASE-CLAUSE-7";

  /** The scripted model: asks for searchKnowledge, then answers from what the tool returned. */
  private static final class ScriptedModel implements ChatModel {
    final List<List<String>> offeredToolsPerRound = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse call(Prompt prompt) {
      return next(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.defer(() -> Flux.just(next(prompt)));
    }

    private ChatResponse next(Prompt prompt) {
      offeredToolsPerRound.add(
          prompt.getOptions() instanceof ToolCallingChatOptions tools
              ? tools.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList()
              : List.of());
      List<Message> history = prompt.getInstructions();
      Message last = history.getLast();
      if (last instanceof ToolResponseMessage result) {
        String data =
            result.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .collect(Collectors.joining());
        return answer("From the knowledge base: " + data);
      }
      var call =
          new AssistantMessage.ToolCall(
              "call-1", "function", "searchKnowledge", "{\"query\":\"rent\"}");
      return new ChatResponse(
          List.of(
              new Generation(
                  AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    private static ChatResponse answer(String text) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
  }

  /** An emitter that keeps what the client would receive, event by event. */
  private static final class RecordingEmitter extends SseEmitter {
    final List<String> events = new CopyOnWriteArrayList<>();

    RecordingEmitter() {
      super(0L);
    }

    @Override
    public synchronized void send(SseEventBuilder builder) throws IOException {
      events.add(
          builder.build().stream()
              .map(part -> String.valueOf(part.getData()))
              .collect(Collectors.joining()));
    }
  }

  /** The rows 30-jobs-config.sql seeds: the pipeline a fresh deployment runs. */
  private static List<InterceptorConfig> seededPipeline() {
    List<InterceptorConfig> rows = new ArrayList<>();
    String[] keys = {
      "UserContextResolver", "SystemContextInjector", "RagInterceptor", "EntitlementInterceptor",
      "McpInterceptor", "BrandContextInterceptor", "MemoryInterceptor", "RefinerInterceptor",
      "RouterInterceptor", "ToolInterceptor", "MediaInterceptor", "UserContextInterceptor"
    };
    for (int i = 0; i < keys.length; i++) {
      rows.add(new InterceptorConfig(keys[i], keys[i], i + 1, true, ""));
    }
    return rows;
  }

  private final ScriptedModel model = new ScriptedModel();

  private ApplicationContextRunner runner(String provider) {
    return runner(provider, () -> new SpringAiChatInvocation(model, noFactory()));
  }

  @SuppressWarnings("unchecked")
  private static DynamicChatModelFactory<ChatModel> noFactory() {
    return mock(DynamicChatModelFactory.class);
  }

  private ApplicationContextRunner runner(
      String provider, java.util.function.Supplier<ChatInvocation> invocation) {
    KnowledgeService knowledge = mock(KnowledgeService.class);
    when(knowledge.retrieveContext(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn("Leases renew every July.");
    when(knowledge.searchSources(any(), any(), any())).thenReturn(KNOWLEDGE_HIT);
    PipelineConfigProvider pipeline = mock(PipelineConfigProvider.class);
    when(pipeline.findAllOrdered()).thenReturn(seededPipeline());
    ModelCatalogProvider catalog = mock(ModelCatalogProvider.class);
    when(catalog.getActiveChatModel()).thenReturn(Optional.of("stub-model"));
    when(catalog.getCatalog()).thenReturn(Optional.empty());
    SpringAiToolRegistry registry = new SpringAiToolRegistry(null, null, null);

    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CoreServicesAutoConfiguration.class,
                EnrichmentAutoConfiguration.class,
                ToolingAutoConfiguration.class))
        .withPropertyValues(
            "orazaka.core.knowledge.base-url=http://knowledge.invalid",
            "orazaka.core.knowledge.service-secret=" + "s".repeat(32),
            "spring.ai.ollama.base-url=http://ollama.invalid")
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withBean(ChatStreamRegistry.class, ChatStreamRegistry::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(
            CoreProperties.class,
            () ->
                new CoreProperties(
                    provider,
                    new CoreProperties.RagConfig(true, "pgvector", 3),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .withBean(SecurityProperties.class, () -> mock(SecurityProperties.class))
        .withBean(PipelineConfigProvider.class, () -> pipeline)
        .withBean(ModelCatalogProvider.class, () -> catalog)
        .withBean(KnowledgeService.class, () -> knowledge)
        .withBean(ToolRegistry.class, () -> registry)
        .withBean(ToolOptions.class, SpringAiToolOptions::new)
        .withBean(
            ToolResolution.class, () -> new SpringAiToolResolution(null, null, registry, null))
        .withBean(SpringAiPromptAssembly.class, SpringAiPromptAssembly::new)
        .withBean(ChatInvocation.class, invocation)
        .withBean(UserCredentialsProvider.class, () -> mock(UserCredentialsProvider.class))
        .withBean(SemanticRoutingEngine.class, () -> mock(SemanticRoutingEngine.class))
        .withBean(DefaultToolCatalogue.class, () -> new DefaultToolCatalogue(registry, knowledge))
        .withBean(SpeechSynthesis.class, () -> mock(SpeechSynthesis.class))
        .withBean(ImageGeneratorClient.class, () -> mock(ImageGeneratorClient.class))
        .withBean(CatalogModelManager.class, () -> mock(CatalogModelManager.class))
        .withBean(
            ChatSessionPersistenceProvider.class, () -> mock(ChatSessionPersistenceProvider.class))
        .withBean(JobPersistenceProvider.class, () -> mock(JobPersistenceProvider.class))
        .withBean(RuntimeConfigProvider.class, () -> mock(RuntimeConfigProvider.class))
        .withBean(AccessAudit.class, () -> mock(AccessAudit.class))
        .withBean(SemanticClassifierPort.class, () -> mock(SemanticClassifierPort.class))
        .withUserConfiguration(Glue.class);
  }

  /** What the Spring AI adapter's own auto-configuration adds on top of the engine. */
  @Configuration
  static class Glue {
    @Bean
    ChatGeneratorClient chatGeneratorClient(Engine engine) {
      return new ChatGeneratorClientImpl(engine);
    }
  }

  @ParameterizedTest(name = "provider={0}")
  @ValueSource(strings = {"openai", "ollama", "anthropic", "gemini"})
  void aStreamedTurnReachesTheClientWithAToolCallEventAndTheToolResultInTheAnswer(String provider) {
    runner(provider)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              String wire = streamOneTurn(context);
              assertThat(model.offeredToolsPerRound)
                  .as("the model is offered searchKnowledge on its first round")
                  .isNotEmpty()
                  .allSatisfy(round -> assertThat(round).contains("searchKnowledge"));
              assertThat(wire)
                  .as("the client receives a tool-call event")
                  .contains("event:tool-call")
                  .contains("searchKnowledge");
              assertThat(wire)
                  .as("the tool's result is what the answer is made of")
                  .contains(KNOWLEDGE_HIT);
            });
  }

  /** An OpenAI-compatible endpoint: what the engine's real model client talks to on the wire. */
  private static final class OpenAiWire implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    OpenAiWire() throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/v1/chat/completions", this::handle);
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(body);
      List<String> chunks =
          body.contains("\"role\":\"tool\"")
              ? List.of(
                  delta("{\"role\":\"assistant\",\"content\":\"From the knowledge base: \"}", null),
                  delta(
                      "{\"content\":\""
                          + (body.contains(KNOWLEDGE_HIT) ? KNOWLEDGE_HIT : "NOTHING")
                          + "\"}",
                      null),
                  delta("{}", "stop"))
              : List.of(
                  delta(
                      "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                          + "\"type\":\"function\",\"function\":{\"name\":\"searchKnowledge\",\"arguments\":\"\"}}]}",
                      null),
                  delta(
                      "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"query\\\":\\\"rent\\\"}\"}}]}",
                      null),
                  delta("{}", "tool_calls"));
      StringBuilder out = new StringBuilder();
      chunks.forEach(c -> out.append("data: ").append(c).append("\n\n"));
      out.append("data: [DONE]\n\n");
      byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }

    private static String delta(String delta, String finish) {
      return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
          + "\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":"
          + delta
          + ",\"finish_reason\":"
          + (finish == null ? "null" : "\"" + finish + "\"")
          + "}]}";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  void theRealModelClientSendsTheToolOnTheWireAndStreamedToolCallDeltasRunIt() throws Exception {
    try (OpenAiWire wire = new OpenAiWire()) {
      ModelEndpointResolver endpoints = mock(ModelEndpointResolver.class);
      var endpoint = new ModelEndpoint("local", wire.baseUrl(), null, "stub-model");
      when(endpoints.resolve(any())).thenReturn(Optional.of(endpoint));
      when(endpoints.resolveDefault(any())).thenReturn(Optional.of(endpoint));
      ChatModel routed =
          new RoutedChatModel(new DynamicModelRouter(endpoints, new UniversalProxyChatProvider()));
      runner("openai", () -> new SpringAiChatInvocation(routed, noFactory()))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                String wireText = streamOneTurn(context);
                assertThat(wire.requests)
                    .as("the first request carries the tool definition")
                    .isNotEmpty()
                    .first()
                    .asString()
                    .contains("\"tools\"")
                    .contains("searchKnowledge");
                assertThat(wireText)
                    .as("the client receives a tool-call event")
                    .contains("event:tool-call")
                    .contains("searchKnowledge");
                assertThat(wireText)
                    .as("the tool's result reaches the answer")
                    .contains(KNOWLEDGE_HIT);
              });
    }
  }

  /** One streamed turn through the real controller; returns what the client received. */
  private static String streamOneTurn(
      org.springframework.boot.test.context.assertj.AssertableApplicationContext context)
      throws InterruptedException {
    var contextService = mock(ContextService.class);
    when(contextService.resolve(any(), any()))
        .thenReturn(
            new Context("user-1", CONVERSATION, Map.of(), Set.of(new Authority("ROLE_USER"))));
    var emitter = new RecordingEmitter();
    var gateway =
        new SseStreamGateway() {
          @Override
          public SseEmitter createEmitter() {
            return emitter;
          }
        };
    var controller =
        new ChatController(
            context.getBean(com.krizaka.orazaka.core.domain.ports.inbound.AiClient.class),
            contextService,
            context.getBean(DynamicPipelineExecutor.class),
            context.getBean(ChatStreamRegistry.class),
            gateway,
            mock(ChatSessionService.class),
            mock(com.krizaka.orazaka.core.domain.ports.outbound.ChatMemoryStore.class),
            new JobsProperties(30));

    controller.streamChatPost(
        CONVERSATION,
        new ChatStreamRequest(
            "What does my lease say about the rent?", List.of(), null, null, null, null, "default"),
        Persona.freeUser());

    long deadline = System.nanoTime() + 10_000_000_000L;
    while (emitter.events.stream().noneMatch(e -> e.contains(KNOWLEDGE_HIT))
        && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    return String.join("\n", emitter.events);
  }
}
