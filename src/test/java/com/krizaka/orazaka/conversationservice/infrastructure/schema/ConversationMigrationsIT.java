package com.krizaka.orazaka.conversationservice.infrastructure.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The conversation schema is code (krizaka-internal#2255): {@code db/migration} applied by Flyway
 * on an empty database creates every table; applied a second time it runs nothing and still
 * validates. {@code orazaka_db} is shared with the job and governance contexts, so the same V1 must
 * also apply on a database where another context already created its tables.
 */
@SpringJUnitConfig(ConversationMigrationsIT.NoBeans.class)
class ConversationMigrationsIT extends AbstractContainerIntegrationTest {

  /** The shared base wires the container's ports into the Environment; no bean is needed. */
  @Configuration
  static class NoBeans {}

  private static final int TABLES = 7;

  private static final String CONTEXT_TABLES =
      "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
          + " AND table_name NOT LIKE 'flyway_history%'";

  @Autowired private Environment environment;

  private String user() {
    return environment.getRequiredProperty("spring.datasource.username");
  }

  private String credential() {
    return environment.getRequiredProperty("spring.datasource.password");
  }

  private String url(String database) {
    String url = environment.getRequiredProperty("spring.datasource.url");
    return url.substring(0, url.lastIndexOf('/') + 1) + database;
  }

  private Connection connect(String database) throws Exception {
    return DriverManager.getConnection(url(database), user(), credential());
  }

  private void freshDatabase(String database) throws Exception {
    try (Connection admin = connect("postgres");
        Statement statement = admin.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + database);
      statement.execute("CREATE DATABASE " + database);
    }
  }

  /** The settings of application.yml: own history table, baseline at 0 (see the comment there). */
  private Flyway flyway(String database, String locations, String table) {
    return Flyway.configure()
        .dataSource(url(database), user(), credential())
        .locations(locations)
        .table(table)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .outOfOrder(false)
        .load();
  }

  private Flyway conversation(String database) {
    return flyway(database, "classpath:db/migration", "flyway_history_conversation");
  }

  private static long count(Statement statement, String sql) throws Exception {
    try (ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  @Test
  void anEmptyDatabaseGetsTheWholeSchemaAndASecondRunAppliesNothing() throws Exception {
    String database = "conversation_migrations_check";
    freshDatabase(database);

    assertThat(conversation(database).migrate().migrationsExecuted)
        .as("first run on an empty database")
        .isEqualTo(1);
    assertThat(conversation(database).migrate().migrationsExecuted).as("second run").isZero();
    conversation(database).validate();

    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      assertThat(count(statement, CONTEXT_TABLES)).as("the context's tables").isEqualTo(TABLES);
      assertThat(count(statement, "SELECT count(*) FROM app_config"))
          .as("the app-version reference rows")
          .isEqualTo(6);
    }
  }

  @Test
  void aDatabaseAnotherContextAlreadyFilledStillGetsConversationV1() throws Exception {
    String database = "orazaka_shared_check";
    freshDatabase(database);
    Path job = Files.createTempDirectory("job-migration");
    Files.writeString(
        job.resolve("V1__job_baseline.sql"), "CREATE TABLE job_stand_in (id BIGINT PRIMARY KEY);");
    String jobLocation = "filesystem:" + job;

    // The job context arrives first and leaves a non-empty schema behind it.
    assertThat(flyway(database, jobLocation, "flyway_history_job").migrate().migrationsExecuted)
        .isEqualTo(1);
    // Baselining at the default 1 would mark V1 applied and skip it; baselining at 0 runs it.
    assertThat(conversation(database).migrate().migrationsExecuted)
        .as("conversation V1 on a database another context filled")
        .isEqualTo(1);
    // Each context keeps its own history and neither disturbs the other.
    assertThat(flyway(database, jobLocation, "flyway_history_job").migrate().migrationsExecuted)
        .isZero();
    conversation(database).validate();

    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      assertThat(count(statement, CONTEXT_TABLES)).isEqualTo(TABLES + 1);
    }
  }

  @Test
  void theServiceConfigurationBaselinesAtZeroAndKeepsItsOwnHistoryTable() throws Exception {
    String yml =
        Files.readString(
            Path.of(System.getProperty("user.dir"), "src/main/resources/application.yml"));
    assertThat(yml)
        .contains("table: flyway_history_conversation")
        .contains("baseline-version: \"0\"")
        .contains("clean-disabled: true")
        .contains("validate-on-migrate: true");
  }

  @Test
  void theLocalBootstrapCreatesNoTableAndSeedsNothing() throws Exception {
    // One source for the schema: a table in the initdb file would be a second copy, and the one a
    // deployed database never gets.
    String initdb =
        Files.readString(
            Path.of(System.getProperty("user.dir"), "infra/initdb/20-conversation.sql"));
    assertThat(initdb)
        .doesNotContainIgnoringCase("CREATE TABLE")
        .doesNotContainIgnoringCase("INSERT INTO");
  }
}
