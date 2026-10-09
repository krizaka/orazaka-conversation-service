package com.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.users.domain.model.User;
import com.orazaka.conversationservice.application.service.AssetService;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the media a job produced.
 *
 * <p>Replaces the static resource handler that used to publish the whole upload tree. That handler
 * could answer "is this caller authenticated" and nothing else, so the first path segment was in
 * effect the authorisation decision and any tenant could read any other's files by editing it. Here
 * the owner is read from the job record and the URL only names a file inside it.
 *
 * <p>Named for the resource it serves, not the flow it belongs to [ERR-128].
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

  private static final Logger logger = LoggerFactory.getLogger(AssetController.class);

  private static final String ADMIN = "ROLE_ADMIN";

  private final AssetService assetService;

  public AssetController(AssetService assetService) {
    this.assetService = assetService;
  }

  /**
   * Streams one asset to its owner, or to an administrator.
   *
   * <p>{@code Range} is honoured by returning the resource itself rather than its bytes: Spring's
   * resource handling answers a partial request from the stream, which is what lets the run detail
   * seek inside an MP4. Reading the file into a {@code byte[]} would make range handling trivial
   * and put a whole video in heap per concurrent viewer.
   *
   * @param jobId the job that produced the file
   * @param filename the file within that job's output
   * @param user the authenticated caller
   * @return {@code 200} (or {@code 206} for a range) with the stream, {@code 404} otherwise
   * @throws IOException if the file's length cannot be read
   */
  @GetMapping("/{jobId}/{filename}")
  public ResponseEntity<Resource> read(
      @PathVariable String jobId, @PathVariable String filename, @AuthenticationPrincipal User user)
      throws IOException {

    boolean isAdmin = user.authorities().contains(ADMIN);
    Optional<Resource> asset =
        assetService.resolveFor(jobId, filename, user.id().toString(), isAdmin);

    if (asset.isEmpty()) {
      // 404 for "absent", "not yours" and "unreadable" alike — a 403 would confirm the file
      // exists and turn this endpoint into an enumeration oracle.
      logger.debug("Asset {}/{} not readable by {}", jobId, filename, user.id());
      return ResponseEntity.notFound().build();
    }

    Resource resource = asset.get();
    MediaType contentType =
        MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);

    return ResponseEntity.ok()
        // Authenticated media must never enter a shared cache: no-store, and private so an
        // intermediary cannot retain it for the next caller.
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .contentType(contentType)
        .contentLength(resource.contentLength())
        .body(resource);
  }
}
