package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orazaka.assets.application.service.EncryptedAssetService;
import com.orazaka.assets.infrastructure.adapter.FileMasterKeyProvider;
import com.orazaka.assets.infrastructure.config.AssetEncryptionProperties;
import com.orazaka.conversationservice.application.service.AssetService;
import com.orazaka.conversationservice.infrastructure.config.RouterProperties;
import com.orazaka.identity.domain.model.User;
import com.orazaka.persistence.domain.model.JobDto;
import com.orazaka.persistence.domain.ports.inbound.JobPersistenceProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The ownership matrix for {@code GET /api/v1/assets/{jobId}/{filename}}.
 *
 * <p>This is the test whose absence let the finding live: the previous static handler was covered
 * by nothing that asked "may *this* caller read *this* file", because a static handler has no such
 * concept. Every case below is a decision the controller now has to make.
 *
 * <p>Standalone MockMvc rather than a booted context: the decision under test is entirely in {@code
 * AssetService} + the controller, and requiring Postgres, Redis, RabbitMQ and the identity
 * directory to assert "B cannot read A's file" would make the most important test in this wave the
 * slowest and the most fragile. The anonymous case is a filter-chain rule and is asserted by the §5
 * gate against the running edge.
 */
class AssetControllerIT {

  private static final String OWNER = "11111111-1111-4111-8111-111111111111";
  private static final String OTHER = "22222222-2222-4222-8222-222222222222";
  private static final String ADMIN = "33333333-3333-4333-8333-333333333333";
  private static final String JOB = "job-7";
  private static final String FILE = "image.png";
  private static final byte[] BYTES = "the owner's pixels".getBytes();

  private static final int BLOCK_SIZE = 4096;

  @TempDir Path uploadRoot;

  private MockMvc mockMvc;
  private EncryptedAssetService assets;
  private AssetEncryptionProperties encryption;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(uploadRoot.resolve(OWNER).resolve(JOB).resolve("output"));

    // The store is encrypted end to end here, exactly as it is in production: the fixture writes
    // through EncryptedAssetService, so every assertion below is made against ciphertext on disk.
    Path keyFile = uploadRoot.resolve("master.key");
    FileMasterKeyProvider.addKey(keyFile, "k1");
    encryption = new AssetEncryptionProperties(true, keyFile.toString(), BLOCK_SIZE, false);
    assets = new EncryptedAssetService(new FileMasterKeyProvider(keyFile), BLOCK_SIZE);
    assets.write(uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve(FILE), BYTES);

    JobPersistenceProvider jobs = new StubJobs();
    RouterProperties properties = routerProperties(uploadRoot.toString());

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AssetController(new AssetService(jobs, properties, assets, encryption)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("the owner reads their own asset and gets the bytes")
  void ownerReadsOwnAsset() throws Exception {
    authenticate(OWNER, "ROLE_USER");
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", JOB, FILE))
        .andExpect(status().isOk())
        .andExpect(content().bytes(BYTES));
  }

  @Test
  @DisplayName("another actor gets 404 — never 403, which would confirm the file exists")
  void otherActorIsNotFound() throws Exception {
    authenticate(OTHER, "ROLE_USER");
    // The whole finding in one assertion: before this, the same request returned the file.
    mockMvc.perform(get("/api/v1/assets/{job}/{file}", JOB, FILE)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an unknown job is 404, indistinguishable from someone else's job")
  void unknownJobIsNotFound() throws Exception {
    authenticate(OWNER, "ROLE_USER");
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", "no-such-job", FILE))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an admin may read another actor's asset — the jobs dashboard needs it")
  void adminReadsAnyAsset() throws Exception {
    authenticate(ADMIN, "ROLE_ADMIN");
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", JOB, FILE))
        .andExpect(status().isOk())
        .andExpect(content().bytes(BYTES));
  }

  @Test
  @DisplayName("a missing file is 404 even for its owner")
  void missingFileIsNotFound() throws Exception {
    authenticate(OWNER, "ROLE_USER");
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", JOB, "absent.png"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("authenticated media is never cacheable by a shared cache")
  void responseIsNotCacheable() throws Exception {
    authenticate(OWNER, "ROLE_USER");
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", JOB, FILE))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(header().string("Accept-Ranges", "bytes"));
  }

  /**
   * Puts one actor in the security context.
   *
   * <p>Through {@code SecurityContextHolder} rather than the request principal, because
   * {@code @AuthenticationPrincipal} resolves from the context — setting the request principal
   * leaves the argument null and every case passes for the wrong reason.
   */
  private static void authenticate(String id, String authority) {
    User user =
        new User(
            UUID.fromString(id),
            "actor-" + id,
            id + "@orazaka.test",
            true,
            Set.of(authority),
            Map.of(),
            java.util.List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            user, "token", java.util.List.of(new SimpleGrantedAuthority(authority))));
    SecurityContextHolder.setContext(context);
  }

  /** Only {@code getJob} is exercised; the rest of the port is irrelevant to this decision. */
  private static final class StubJobs implements JobPersistenceProvider {

    @Override
    public String createJob(
        String userId,
        String featureKey,
        Map<String, Object> payload,
        com.orazaka.jobs.domain.model.DataClass dataClass) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String createJob(
        String jobId,
        String userId,
        String featureKey,
        Map<String, Object> payload,
        com.orazaka.jobs.domain.model.DataClass dataClass) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateJobStatus(
        String jobId, String status, Map<String, Object> result, String errorMessage) {
      throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<JobDto> getJobsByUserId(
        String userId, org.springframework.data.domain.Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<JobDto> getAllJobs(
        org.springframework.data.domain.Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<JobDto> findJobsByStatuses(java.util.Collection<String> statuses) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void purgeJobsByUserId(String userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JobDto> getJob(String jobId) {
      if (!JOB.equals(jobId)) {
        return Optional.empty();
      }
      return Optional.of(
          new JobDto(
              JOB,
              OWNER,
              "orazaka.core.media.image",
              "SUCCEEDED",
              Map.of(),
              Map.of(),
              null,
              Instant.now(),
              Instant.now()));
    }
  }

  private static RouterProperties routerProperties(String directory) {
    return new RouterProperties(
        new RouterProperties.UploadsConfig(directory, "/api/v1/assets/**", 0));
  }

  // ── ADR-054: the three things encryption at rest has to be able to show ────

  @Test
  @DisplayName("[ADR-054] written then read back through the API gives the same bytes")
  void theApiRoundTripsExactly() throws Exception {
    byte[] document = new byte[3 * BLOCK_SIZE + 137];
    new java.util.Random(7).nextBytes(document);
    Path file = uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve("scan.pdf");
    assets.write(file, document);

    authenticate(OWNER, "ROLE_USER");
    byte[] served =
        mockMvc
            .perform(get("/api/v1/assets/{job}/{file}", JOB, "scan.pdf"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertArrayEquals(document, served, "the API must hand back exactly what was stored");
  }

  @Test
  @DisplayName("[ADR-054] the same file read straight off the disk is unreadable")
  void theFileOnDiskIsNotTheDocument() throws Exception {
    byte[] document =
        "carte d'identité 940123456789 — Claire DUBOIS"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Path file = uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve("id.txt");
    assets.write(file, document);

    byte[] onDisk = Files.readAllBytes(file);
    assertFalse(
        new String(onDisk, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Claire DUBOIS"),
        "the identifying document must not be legible to anything that opens the file");
    assertFalse(java.util.Arrays.equals(document, onDisk));
    // And it is an envelope rather than merely different bytes.
    assertEquals('O', onDisk[0]);
    assertEquals('R', onDisk[1]);
    assertEquals('Z', onDisk[2]);
  }

  @Test
  @DisplayName("[ADR-054] a Range on a video returns the right segment, decrypting one block")
  void aRangeReturnsTheRightSegment() throws Exception {
    byte[] video = new byte[10 * BLOCK_SIZE];
    new java.util.Random(11).nextBytes(video);
    Path file = uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve("clip.mp4");
    assets.write(file, video);

    authenticate(OWNER, "ROLE_USER");
    int from = 7 * BLOCK_SIZE + 500;
    int to = from + 999;
    byte[] segment =
        mockMvc
            .perform(
                get("/api/v1/assets/{job}/{file}", JOB, "clip.mp4")
                    .header("Range", "bytes=" + from + "-" + to))
            .andExpect(status().isPartialContent())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertArrayEquals(
        java.util.Arrays.copyOfRange(video, from, to + 1),
        segment,
        "a range deep in a video must be the bytes at that offset, not the file from zero");
  }

  @Test
  @DisplayName("[ADR-054] a plaintext file is NOT served once the migration tolerance is off")
  void plaintextIsRefusedAfterTheMigration() throws Exception {
    Path legacy = uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve("legacy.png");
    Files.write(legacy, "still in the clear".getBytes());

    authenticate(OWNER, "ROLE_USER");
    // 404, like every other unreadable case: a permanent tolerance is a permanent door.
    mockMvc
        .perform(get("/api/v1/assets/{job}/{file}", JOB, "legacy.png"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("[ADR-054] and IS served while it is on — the cutover has to be crossable")
  void plaintextIsServedDuringTheMigration() throws Exception {
    Path legacy = uploadRoot.resolve(OWNER).resolve(JOB).resolve("output").resolve("legacy2.png");
    byte[] bytes = "still in the clear".getBytes();
    Files.write(legacy, bytes);

    MockMvc tolerant =
        MockMvcBuilders.standaloneSetup(
                new AssetController(
                    new AssetService(
                        new StubJobs(),
                        routerProperties(uploadRoot.toString()),
                        assets,
                        new AssetEncryptionProperties(
                            true, encryption.masterKeyFile(), BLOCK_SIZE, true))))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    authenticate(OWNER, "ROLE_USER");
    tolerant
        .perform(get("/api/v1/assets/{job}/{file}", JOB, "legacy2.png"))
        .andExpect(status().isOk())
        .andExpect(content().bytes(bytes));
  }
}
