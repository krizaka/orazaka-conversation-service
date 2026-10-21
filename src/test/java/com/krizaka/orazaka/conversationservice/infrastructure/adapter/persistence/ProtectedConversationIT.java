package com.krizaka.orazaka.conversationservice.infrastructure.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore;
import com.krizaka.orazaka.conversationservice.domain.port.ProtectedConversationStore.Erased;
import com.krizaka.orazaka.test.architecture.SqlBoundaryRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The memory of a regulated pack's voice and listening turns is erased on request and ages out
 * (orazaka#301), against the <b>real</b> DDL of {@code 20-conversation.sql}.
 *
 * <p>The proof is not a list of tables that happen to be checked: the person's words are searched
 * for in <b>every table of the schema</b>.
 */
class ProtectedConversationIT {

  private static final String ERIC = "550e8400-e29b-41d4-a716-446655440099";
  private static final String OTHER = "550e8400-e29b-41d4-a716-446655440100";
  private static final String WORDS = "je n'arrive plus a dormir depuis la separation";
  private static final String OTHER_WORDS = "des mots qui ne sont pas a eric";
  private static final String CHAT_WORDS = "une question ordinaire sur la meteo";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("orazaka_db")
          .withUsername("postgres")
          .withPassword("postgres");

  /** The chat tables and the protected index, out of the real initdb file (not the whole file). */
  private static final Pattern DDL =
      Pattern.compile(
          "(?s)CREATE TABLE orazaka_chat_sessions \\(.*?\\n\\);"
              + "|CREATE TABLE orazaka_chat_messages \\(.*?\\n\\);"
              + "|CREATE TABLE IF NOT EXISTS orazaka_protected_conversation \\(.*?\\n\\);");

  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static ProtectedConversationStore service;

  @BeforeAll
  static void start() throws IOException {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    Path initDb = conversationInitDb();
    Matcher statement = DDL.matcher(Files.readString(initDb));
    int executed = 0;
    while (statement.find()) {
      jdbc.execute(statement.group());
      executed++;
    }
    assertThat(executed).as("the three tables read from the initdb file").isEqualTo(3);
    service =
        new JdbcProtectedConversationStoreAdapter(
            jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private static Path conversationInitDb() {
    Path dir = Path.of(System.getProperty("user.dir"));
    return SqlBoundaryRules.locateInitDb(dir).resolve("20-conversation.sql");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM orazaka_chat_messages");
    jdbc.update("DELETE FROM orazaka_chat_sessions");
    jdbc.update("DELETE FROM orazaka_protected_conversation");
  }

  private static void conversation(String id, String actor, String words, boolean protectedPack) {
    jdbc.update(
        "INSERT INTO orazaka_chat_messages (conversation_id, role, content) VALUES (?, 'user', ?)",
        id,
        words);
    jdbc.update(
        "INSERT INTO orazaka_chat_messages (conversation_id, role, content) VALUES (?, 'assistant', ?)",
        id,
        "Je t'ecoute. " + words);
    jdbc.update(
        "INSERT INTO orazaka_chat_sessions (id, user_id, title) VALUES (?, ?, ?)",
        id,
        actor,
        words);
    if (protectedPack) {
      service.record(id, actor, "inst-" + id, "bien-etre");
    }
  }

  private static int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private static int tablesMentioning(String words) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
    int total = 0;
    for (String table : tables) {
      total +=
          count(
              "SELECT count(*) FROM \"" + table + "\" t WHERE t::text ILIKE ?", "%" + words + "%");
    }
    return total;
  }

  @Test
  @DisplayName("erase removes a person's protected conversations from every table, and only theirs")
  void eraseRemovesThePersonsProtectedConversationsOnly() {
    conversation("conv-a", ERIC, WORDS, true);
    conversation("conv-b", ERIC, WORDS, true);
    conversation("conv-other", OTHER, OTHER_WORDS, true);
    conversation("conv-chat", ERIC, CHAT_WORDS, false);
    assertThat(tablesMentioning(WORDS)).as("stored before the erasure").isPositive();

    Erased erased = service.eraseOf(ERIC, "bien-etre");

    assertThat(erased).isEqualTo(new Erased(2, 4));
    assertThat(tablesMentioning(WORDS)).as("the words, in any table of the schema").isZero();
    assertThat(tablesMentioning(OTHER_WORDS)).as("somebody else's turns").isPositive();
    assertThat(tablesMentioning(CHAT_WORDS))
        .as("an ordinary chat of the same person is not a wellbeing conversation")
        .isPositive();
    assertThat(
            count("SELECT count(*) FROM orazaka_protected_conversation WHERE actor_id = ?", ERIC))
        .isZero();
  }

  @Test
  @DisplayName("erase is idempotent, and a pack that holds none erases nothing")
  void eraseIsIdempotent() {
    conversation("conv-a", ERIC, WORDS, true);

    assertThat(service.eraseOf(ERIC, "autre-pack")).isEqualTo(new Erased(0, 0));
    assertThat(tablesMentioning(WORDS)).isPositive();
    service.eraseOf(ERIC, "bien-etre");

    assertThat(service.eraseOf(ERIC, "bien-etre")).isEqualTo(new Erased(0, 0));
  }

  @Test
  @DisplayName("a conversation ages out after the retention window, counted from its last turn")
  void agesOut() {
    conversation("conv-old", ERIC, WORDS, true);
    conversation("conv-fresh", ERIC, OTHER_WORDS, true);
    jdbc.update(
        "UPDATE orazaka_protected_conversation SET last_seen_at = now() - interval '31 days'"
            + " WHERE conversation_id = 'conv-old'");

    Erased purged = service.eraseOlderThan(30);

    assertThat(purged).isEqualTo(new Erased(1, 2));
    assertThat(tablesMentioning(WORDS)).isZero();
    assertThat(tablesMentioning(OTHER_WORDS)).isPositive();
  }

  @Test
  @DisplayName("a new turn in an old conversation keeps it inside the window")
  void aNewTurnRefreshesTheWindow() {
    conversation("conv-live", ERIC, WORDS, true);
    jdbc.update(
        "UPDATE orazaka_protected_conversation SET last_seen_at = now() - interval '31 days'");

    service.record("conv-live", ERIC, "inst-conv-live", "bien-etre");

    assertThat(service.eraseOlderThan(30)).isEqualTo(new Erased(0, 0));
    assertThat(tablesMentioning(WORDS)).isPositive();
  }
}
