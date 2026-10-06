package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.orazaka.core.domain.ports.inbound.McpService;
import com.orazaka.identity.domain.model.User;
import com.orazaka.persistence.domain.model.PlatformMcpServerDto;
import com.orazaka.persistence.domain.model.UserMcpServerDto;
import com.orazaka.persistence.domain.ports.inbound.PlatformMcpServerPersistenceProvider;
import com.orazaka.persistence.domain.ports.inbound.UserMcpServerPersistenceProvider;
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
    return ResponseEntity.ok(userMcpServerPersistenceProvider.findByUserId(user.id().toString()));
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
    UserMcpServerDto saved = userMcpServerPersistenceProvider.save(payload);
    return ResponseEntity.ok(saved);
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
      return ResponseEntity.status(403).body("Access Denied");
    }
    userMcpServerPersistenceProvider.deleteById(id);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/servers/platform")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull List<PlatformMcpServerDto>> getPlatformServers() {
    return ResponseEntity.ok(platformMcpServerPersistenceProvider.findAll());
  }

  @PostMapping("/servers/platform")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull Object> savePlatformServer(
      @RequestBody PlatformMcpServerDto payload) {
    if (payload.url() == null && payload.command() == null) {
      return ResponseEntity.badRequest().body("Either URL or Command must be provided");
    }
    PlatformMcpServerDto saved = platformMcpServerPersistenceProvider.save(payload);
    return ResponseEntity.ok(saved);
  }

  @DeleteMapping("/servers/platform/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<@NotNull Void> deletePlatformServer(@PathVariable Integer id) {
    platformMcpServerPersistenceProvider.deleteById(id);
    return ResponseEntity.ok().build();
  }

  /** DTO representing basic tool definition info. */
  public static record ToolInfoDto(String name, String description, String inputSchema) {
    public ToolInfoDto {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(description, "description must not be null");
      Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    }
  }
}
