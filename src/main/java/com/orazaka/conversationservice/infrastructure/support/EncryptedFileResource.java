package com.orazaka.conversationservice.infrastructure.support;

import com.orazaka.assets.application.service.EncryptedAssetService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.AbstractResource;

/**
 * An encrypted file, served as if it were not.
 *
 * <p>Everything above it — {@code AssetController}, Spring's {@code
 * ResourceRegionHttpMessageConverter}, the browser — sees a resource of the plaintext length whose
 * stream yields plaintext. Nothing about ciphertext reaches the transport.
 *
 * <p><b>The whole file is never decrypted to answer a range.</b> Spring serves a {@code Range} by
 * opening this resource and skipping to the start; the stream underneath seeks to the block
 * containing that offset and decrypts one block. That is why the format has blocks: AES-GCM's tag
 * covers a whole message, so a single-message file would make a seek to the last second of a video
 * cost the entire video (ADR-054 §4).
 */
public final class EncryptedFileResource extends AbstractResource {

  private final Path file;
  private final EncryptedAssetService assets;

  /**
   * @param file an encrypted file
   * @param assets the store that can open it
   */
  public EncryptedFileResource(Path file, EncryptedAssetService assets) {
    this.file = file;
    this.assets = assets;
  }

  /** The PLAINTEXT length, read from the header: what {@code Content-Length} must say. */
  @Override
  public long contentLength() throws IOException {
    return assets.plainLength(file);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return assets.read(file);
  }

  /** Named so {@code MediaTypeFactory} still guesses the type from the extension. */
  @Override
  public String getFilename() {
    return file.getFileName().toString();
  }

  @Override
  public String getDescription() {
    return "encrypted asset [" + file + "]";
  }

  @Override
  public boolean exists() {
    return Files.isReadable(file);
  }

  @Override
  public boolean isReadable() {
    return exists();
  }
}
