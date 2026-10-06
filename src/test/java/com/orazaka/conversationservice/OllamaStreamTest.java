package com.orazaka.conversationservice;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

@SpringBootTest
@org.junit.jupiter.api.Disabled("Requires local Ollama instance")
class OllamaStreamTest {

  private static final Logger log = LoggerFactory.getLogger(OllamaStreamTest.class);

  @Autowired private ChatModel chatModel;

  @Test
  void testOllamaStreamingWithOptions() {
    org.junit.jupiter.api.Assertions.assertNotNull(chatModel);
    log.info(">>> STARTING OLLAMA STREAM OPTIONS TEST <<<");

    List<Message> messages =
        List.of(
            new SystemMessage(
                "System constraints:\nUser Primary Industry: technology\nUser AI Behavior: professional\n"),
            new UserMessage("Hello! Tell me a quick fact about Orazaka Corporation."));

    OllamaChatOptions finalOptions =
        OllamaChatOptions.builder().model("llama3.2:3b").temperature(0.7).numCtx(8192).build();

    Prompt prompt = new Prompt(messages, finalOptions);
    try {
      Flux<org.springframework.ai.chat.model.ChatResponse> stream = chatModel.stream(prompt);
      List<org.springframework.ai.chat.model.ChatResponse> responses = stream.collectList().block();
      log.info("Responses received: {}", responses == null ? "null" : responses.size());
      if (responses != null) {
        for (int i = 0; i < responses.size(); i++) {
          var resp = responses.get(i);
          String text =
              resp.getResult() != null && resp.getResult().getOutput() != null
                  ? resp.getResult().getOutput().getText()
                  : "null";
          log.debug("Chunk {}: {}", i, text);
        }
      }
    } catch (Exception e) {
      log.error("Error during streaming: {}", e.getMessage(), e);
    }
    log.info(">>> END OLLAMA STREAM OPTIONS TEST <<<");
  }
}
