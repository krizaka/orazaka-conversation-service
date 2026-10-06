package com.orazaka.conversationservice.infrastructure.adapter.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.orazaka.core.domain.model.mcp.McpToolInfo;
import com.orazaka.core.domain.ports.inbound.McpService;
import com.orazaka.identity.domain.model.User;
import com.orazaka.persistence.domain.model.PlatformMcpServerDto;
import com.orazaka.persistence.domain.model.UserMcpServerDto;
import com.orazaka.persistence.domain.ports.inbound.PlatformMcpServerPersistenceProvider;
import com.orazaka.persistence.domain.ports.inbound.UserMcpServerPersistenceProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class McpControllerTest {
  private static final java.time.Clock FIXED_CLOCK =
      java.time.Clock.fixed(
          java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

  private McpService mcpService;
  private PlatformMcpServerPersistenceProvider platformMcpServerPersistenceProvider;
  private UserMcpServerPersistenceProvider userMcpServerPersistenceProvider;
  private ObjectMapper objectMapper;
  private McpController mcpController;

  @BeforeEach
  void setUp() {
    mcpService = mock(McpService.class);
    platformMcpServerPersistenceProvider = mock(PlatformMcpServerPersistenceProvider.class);
    userMcpServerPersistenceProvider = mock(UserMcpServerPersistenceProvider.class);
    objectMapper = new ObjectMapper(); // Use real Jackson
    mcpController =
        new McpController(
            mcpService,
            platformMcpServerPersistenceProvider,
            userMcpServerPersistenceProvider,
            objectMapper);
  }

  @Test
  void testGetTools() {
    McpToolInfo tool = new McpToolInfo("search", "Search engine", "{}");
    when(mcpService.getAvailableTools()).thenReturn(List.of(tool));

    ResponseEntity<List<McpController.ToolInfoDto>> response = mcpController.getTools();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().size());
    assertEquals("search", response.getBody().get(0).name());
    assertEquals("Search engine", response.getBody().get(0).description());
    assertEquals("{}", response.getBody().get(0).inputSchema());
  }

  @Test
  void testExecuteToolSuccess() {
    when(mcpService.executeTool("search", "{\"query\":\"test\"}")).thenReturn("results");

    ResponseEntity<Map<String, Object>> response =
        mcpController.executeTool("search", Map.of("query", "test"));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("results", response.getBody().get("result"));
  }

  @Test
  void testExecuteToolNotFound() {
    when(mcpService.executeTool(eq("search"), anyString()))
        .thenThrow(new IllegalArgumentException("Not found"));

    ResponseEntity<Map<String, Object>> response =
        mcpController.executeTool("search", Map.of("query", "test"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void testExecuteToolJsonError() throws Exception {
    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));

    McpController controllerWithBadMapper =
        new McpController(
            mcpService,
            platformMcpServerPersistenceProvider,
            userMcpServerPersistenceProvider,
            badMapper);

    ResponseEntity<Map<String, Object>> response =
        controllerWithBadMapper.executeTool("search", Map.of("query", "test"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().containsKey("error"));
  }

  @Test
  void testGetUserServersUnauthorized() {
    ResponseEntity<List<UserMcpServerDto>> response = mcpController.getUserServers(null);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
  }

  @Test
  void testGetUserServersSuccess() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto server =
        new UserMcpServerDto(
            1,
            userId.toString(),
            "Server1",
            "http://server1",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(userMcpServerPersistenceProvider.findByUserId(userId.toString()))
        .thenReturn(List.of(server));

    ResponseEntity<List<UserMcpServerDto>> response = mcpController.getUserServers(user);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().size());
    assertEquals("Server1", response.getBody().get(0).label());
  }

  @Test
  void testSaveUserServerUnauthorized() {
    UserMcpServerDto payload =
        new UserMcpServerDto(
            1, "123", "Server1", "http://server1", "token123", true, Instant.now(FIXED_CLOCK));
    ResponseEntity<Object> response = mcpController.saveUserServer(payload, null);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
  }

  @Test
  void testSaveUserServerIdMismatch() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto payload =
        new UserMcpServerDto(
            1,
            "mismatch-id",
            "Server1",
            "http://server1",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));

    ResponseEntity<Object> response = mcpController.saveUserServer(payload, user);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("User ID mismatch", response.getBody());
  }

  @Test
  void testSaveUserServerEmptyUrl() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto payload =
        new UserMcpServerDto(
            1, userId.toString(), "Server1", "", "token123", true, Instant.now(FIXED_CLOCK));

    ResponseEntity<Object> response = mcpController.saveUserServer(payload, user);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("URL cannot be empty", response.getBody());
  }

  @Test
  void testSaveUserServerSuccess() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto payload =
        new UserMcpServerDto(
            1,
            userId.toString(),
            "Server1",
            "http://server1",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(userMcpServerPersistenceProvider.save(payload)).thenReturn(payload);

    ResponseEntity<Object> response = mcpController.saveUserServer(payload, user);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(payload, response.getBody());
  }

  @Test
  void testDeleteUserServerUnauthorized() {
    ResponseEntity<Object> response = mcpController.deleteUserServer(1, null);
    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
  }

  @Test
  void testDeleteUserServerNotFound() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    when(userMcpServerPersistenceProvider.findById(1)).thenReturn(Optional.empty());

    ResponseEntity<Object> response = mcpController.deleteUserServer(1, user);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void testDeleteUserServerAccessDenied() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto existing =
        new UserMcpServerDto(
            1,
            "different-user-id",
            "Server1",
            "http://server1",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(userMcpServerPersistenceProvider.findById(1)).thenReturn(Optional.of(existing));

    ResponseEntity<Object> response = mcpController.deleteUserServer(1, user);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertEquals("Access Denied", response.getBody());
  }

  @Test
  void testDeleteUserServerSuccess() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "test", "test@test.class", true, Set.of("ROLE_USER"), Map.of());
    UserMcpServerDto existing =
        new UserMcpServerDto(
            1,
            userId.toString(),
            "Server1",
            "http://server1",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(userMcpServerPersistenceProvider.findById(1)).thenReturn(Optional.of(existing));

    ResponseEntity<Object> response = mcpController.deleteUserServer(1, user);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(userMcpServerPersistenceProvider).deleteById(1);
  }

  @Test
  void testGetPlatformServers() {
    PlatformMcpServerDto server =
        new PlatformMcpServerDto(
            1,
            "Platform1",
            "SSE",
            "http://server1",
            "cmd",
            "args",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(platformMcpServerPersistenceProvider.findAll()).thenReturn(List.of(server));

    ResponseEntity<List<PlatformMcpServerDto>> response = mcpController.getPlatformServers();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(1, response.getBody().size());
  }

  @Test
  void testSavePlatformServerBadRequest() {
    PlatformMcpServerDto payload =
        new PlatformMcpServerDto(
            1, "Platform1", "SSE", null, null, "args", "token123", true, Instant.now(FIXED_CLOCK));

    ResponseEntity<Object> response = mcpController.savePlatformServer(payload);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Either URL or Command must be provided", response.getBody());
  }

  @Test
  void testSavePlatformServerSuccess() {
    PlatformMcpServerDto payload =
        new PlatformMcpServerDto(
            1,
            "Platform1",
            "SSE",
            "http://server1",
            null,
            "args",
            "token123",
            true,
            Instant.now(FIXED_CLOCK));
    when(platformMcpServerPersistenceProvider.save(payload)).thenReturn(payload);

    ResponseEntity<Object> response = mcpController.savePlatformServer(payload);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(payload, response.getBody());
  }

  @Test
  void testDeletePlatformServer() {
    ResponseEntity<Void> response = mcpController.deletePlatformServer(1);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(platformMcpServerPersistenceProvider).deleteById(1);
  }
}
