package com.krizaka.orazaka.conversationservice.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The context's bootstrap SQL carries the app-version tables exactly as the kit ships them (the
 * incremental script for an existing database, in the workspace's {@code infra/migrations}, is the
 * same text): a drift would give the service a table its store cannot read.
 */
class AppConfigSchemaTest {

  private static String kitSchema() throws IOException {
    try (InputStream in =
        AppConfigSchemaTest.class
            .getClassLoader()
            .getResourceAsStream("krizaka-app-config-schema.sql")) {
      assertTrue(in != null, "krizaka-app-config-schema.sql is on the classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void theBootstrapContainsTheKitSchemaVerbatim() throws IOException {
    String initdb = Files.readString(Path.of("infra/initdb/20-conversation.sql"));
    assertTrue(
        initdb.contains(kitSchema()),
        "20-conversation.sql must carry krizaka-app-config-schema.sql verbatim");
  }
}
