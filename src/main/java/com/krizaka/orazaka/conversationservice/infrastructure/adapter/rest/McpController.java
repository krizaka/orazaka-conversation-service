package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.core.domain.ports.inbound.McpService;
import com.krizaka.orazaka.persistence.domain.model.PlatformMcpServerDto;
import com.krizaka.orazaka.persistence.domain.model.UserMcpServerDto;
import com.krizaka.orazaka.persistence.domain.ports.inbound.PlatformMcpServerPersistenceProvider;
import com.krizaka.orazaka.persistence.domain.ports.inbound.UserMcpServerPersistenceProvider;
import com.krizaka.users.domain.model.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** REST Controller for inspecting, registering, and managing platform and user MCP servers. */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

  /**
   * What an answer shows instead of a stored token: that one is set, never the token itself
   * (orazaka-ai-engine#58). Sent back unchanged on an edit, it means « keep the stored one ».
   */
  static final String TOKEN_MASK = "********";

  private final McpService mcpService;
  private final PlatformMcpServerPersistenceProvider platformMcpServerPersistenceProvider;
  private final UserMcpServerPersistenceProvider userMcpServerPersistenceProvider;
  private final ObjectMapper objectMapper;

  /**
   * Constructs the McpController.
   *
   * @param mcpService The MCP service.
   * @param platformMcpServerPersistenceProvider Platform MCP persistence provider.
   * @param userMcpServerPersistenceProvider User MCP persistence provider.
   * @param objectMapper Spring-managed JSON ObjectMapper.
   */
  public McpController(
      McpService mcpService,
      PlatformMcpServerPersistenceProvider platformMcpServerPersistenceProvider,
      UserMcpServerPersistenceProvider userMcpServerPersistenceProvider,
      ObjectMapper objectMapper) {
    this.mcpService = Objects.requireNonNull(mcpService, "McpService must not be null");
    this.platformMcpServerPersistenceProvider =
        Objects.requireNonNull(
            platformMcpServerPersistenceProvider,
            "PlatformMcpServerPersistenceProvider must not be null");
    this.userMcpServerPersistenceProvider =
        Objects.requireNonNull(
            userMcpServerPersistenceProvider, "UserMcpServerPersistenceProvider must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
  }

  /**
   * Retrieves all registered tools and their schemas.
   *
   * @return A list of ToolInfoDto objects.
   */
  @GetMapping("/tools")
  public ResponseEntity<@NotNull List<ToolInfoDto>> getTools() {
    List<ToolInfoDto> tools =
        mcpService.getAvailableTools().stream()
            .map(tool -> new ToolInfoDto(tool.name(), tool.description(), tool.inputSchema()))
            .toList();
    return ResponseEntity.ok(tools);
  }

  /**
   * Executes a tool by name with the provided arguments.
   *
   * @param name The name of the tool.
   * @param payload The arguments map.
   * @return A response entity containing the result or error.
   */
  @PostMapping("/tools/{name}/execute")
  public ResponseEntity<@NotNull Map<String, Object>> executeTool(
      @PathVariable String name, @RequestBody Map<String, Object> payload) {
    try {
      String jsonInput = objectMapper.writeValueAsString(payload);
      String result = mcpService.executeTool(name, jsonInput);
      return ResponseEntity.ok(Map.of("result", result));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.notFound().build();
    } catch (JacksonException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Invalid payload: " + e.getMessage()));
    }
  }

  @GetMapping("/servers/user")
  public ResponseEntity<@NotNull List<UserMcpServerDto>> getUserServers(
      @AuthenticationPrincipal User user) {
    if (user == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(
        userMcpServerPersistenceProvider.findByUserId(user.id().toString()).stream()
            .map(McpController::masked)
            .toList());
  }

  @PostMapping("/servers/user")
  public ResponseEntity<@NotNull Object> saveUserServer(
      @RequestBody UserMcpServerDto payload, @AuthenticationPrincipal User user) {
    if (user == null) {
      return ResponseEntity.status(401).build();
    }
    if (!user.id().toString().equals(payload.userId())) {
      return ResponseEntity.badRequest().body("User ID mismatch");
    }
    if (payload.url() == null || payload.url().isBlank()) {
      return ResponseEntity.badRequest().body("URL cannot be empty");
    }
    // An id already held by another user is not ours to overwrite (an upsert is a takeover).
    UserMcpServerDto toSave = payload;
    if (payload.id() != null) {
      var existing = userMcpServerPersistenceProvider.findById(payload.id());
      if (existing.isPresent() && !user.id().toString().equals(existing.get().userId())) {
        return ResponseEntity.notFound().build();
      }
      if (existing.isPresent() && keepsStoredToken(payload.authToken())) {
        toSave =
            new UserMcpServerDto(
                payload.id(),
                payload.userId(),
                payload.label(),
                payload.url(),
                existing.get().authToken(),
                payload.enabled(),
                payload.createdAt());
      }
    }
    UserMcpServerDto saved = userMcpServerPersistenceProvider.save(toSave);
    return ResponseEntity.ok(masked(saved));
  }

  @DeleteMapping("/servers/user/{id}")
  public ResponseEntity<@NotNull Object> deleteUserServer(
      @PathVariable Integer id, @AuthenticationPrincipal User user) {
    if (user == null) {
      return ResponseEntity.status(401).build();
    }
    var existingOpt = userMcpServerPersistenceProvider.findById(id);
    if (existingOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!user.id().toString().equals(existingOpt.get().userId())) {
      // 404, not 403: a foreign id looks exactly like a missing one (#17).
      return ResponseEntity.notFound().build();
    }
    userMcpServerPersistenceProvider.deleteById(id);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/servers/platform")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull List<PlatformMcpServerDto>> getPlatformServers() {
    return ResponseEntity.ok(
        platformMcpServerPersistenceProvider.findAll().stream()
            .map(McpController::masked)
            .toList());
  }

  @PostMapping("/servers/platform")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull Object> savePlatformServer(
      @RequestBody PlatformMcpServerDto payload) {
    if (payload.url() == null && payload.command() == null) {
      return ResponseEntity.badRequest().body("Either URL or Command must be provided");
    }
    PlatformMcpServerDto toSave = payload;
    if (payload.id() != null && keepsStoredToken(payload.authToken())) {
      var existing = platformMcpServerPersistenceProvider.findById(payload.id());
      if (existing.isPresent()) {
        toSave =
            new PlatformMcpServerDto(
                payload.id(),
                payload.label(),
                payload.transportType(),
                payload.url(),
                payload.command(),
                payload.args(),
                existing.get().authToken(),
                payload.enabled(),
                payload.createdAt());
      }
    }
    PlatformMcpServerDto saved = platformMcpServerPersistenceProvider.save(toSave);
    return ResponseEntity.ok(masked(saved));
  }

  @DeleteMapping("/servers/platform/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull Void> deletePlatformServer(@PathVariable Integer id) {
    platformMcpServerPersistenceProvider.deleteById(id);
    return ResponseEntity.ok().build();
  }

  /** DTO representing basic tool definition info. */
  private static boolean keepsStoredToken(String sent) {
    return sent == null || sent.isBlank() || TOKEN_MASK.equals(sent);
  }

  private static UserMcpServerDto masked(UserMcpServerDto dto) {
    return dto == null
        ? null
        : new UserMcpServerDto(
            dto.id(),
            dto.userId(),
            dto.label(),
            dto.url(),
            mask(dto.authToken()),
            dto.enabled(),
            dto.createdAt());
  }

  private static PlatformMcpServerDto masked(PlatformMcpServerDto dto) {
    return dto == null
        ? null
        : new PlatformMcpServerDto(
            dto.id(),
            dto.label(),
            dto.transportType(),
            dto.url(),
            dto.command(),
            dto.args(),
            mask(dto.authToken()),
            dto.enabled(),
            dto.createdAt());
  }

  private static String mask(String token) {
    return token == null || token.isBlank() ? null : TOKEN_MASK;
  }

  public static record ToolInfoDto(String name, String description, String inputSchema) {
    public ToolInfoDto {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(description, "description must not be null");
      Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    }
  }
}
