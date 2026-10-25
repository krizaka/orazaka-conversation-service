package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import com.krizaka.orazaka.tools.application.port.law.LawSettings;
import com.krizaka.orazaka.tools.application.service.LawCorpusCatalogueService;
import com.krizaka.orazaka.tools.application.service.LawManualImportService;
import com.krizaka.orazaka.tools.domain.model.law.LawArticleHistory;
import com.krizaka.orazaka.tools.domain.model.law.LawImportSummary;
import com.krizaka.orazaka.tools.domain.model.law.LawInstrumentSummary;
import com.krizaka.orazaka.tools.domain.model.law.LawWatchReport;
import com.krizaka.orazaka.tools.domain.model.law.ManualLawFile;
import com.krizaka.users.domain.model.User;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Corpus tab of the review queue (ADR-089 §8): the instruments, an article by point in time
 * (metadata only, never its text), the imports and the manual import. Administrators only, every
 * write audited (ADR-085).
 */
@RestController
@RequestMapping(LawReviewController.BASE)
public class LawCorpusAdminController {

  private final LawCorpusCatalogueService catalogue;
  private final LawManualImportService imports;
  private final LawSettings settings;

  public LawCorpusAdminController(
      LawCorpusCatalogueService catalogue, LawManualImportService imports, LawSettings settings) {
    this.catalogue = Objects.requireNonNull(catalogue, "LawCorpusCatalogueService");
    this.imports = Objects.requireNonNull(imports, "LawManualImportService");
    this.settings = Objects.requireNonNull(settings, "LawSettings");
  }

  /**
   * The instruments.
   *
   * @return by jurisdiction and code, with their counts
   */
  @GetMapping("/instruments")
  public List<LawInstrumentSummary> instruments() {
    return catalogue.instruments();
  }

  /**
   * An article by point in time.
   *
   * @param jurisdiction the jurisdiction
   * @param instrument the instrument code
   * @param ref the article reference
   * @param language {@code fr} or {@code en}
   * @param asOf the day, {@code yyyy-MM-dd}
   * @return its versions and the one in force that day; never the text
   */
  @GetMapping("/articles")
  public LawArticleHistory article(
      @RequestParam(required = false) String jurisdiction,
      @RequestParam(required = false) String instrument,
      @RequestParam(required = false) String ref,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) String asOf) {
    return LawReviewProblem.translate(
        () ->
            catalogue.article(
                jurisdiction, instrument, ref, language, LawReviewController.day(asOf, "asOf")));
  }

  /**
   * The imports.
   *
   * @param limit the most rows, 50 by default
   * @return the newest first
   */
  @GetMapping("/imports")
  public List<LawImportSummary> imports(@RequestParam(defaultValue = "50") int limit) {
    return LawReviewProblem.translate(() -> catalogue.imports(limit));
  }

  /**
   * The manual import: the consolidated XML an administrator uploads, with where it came from.
   *
   * @param file the XML
   * @param jurisdiction the jurisdiction
   * @param instrument the instrument code
   * @param language {@code fr} or {@code en}
   * @param datasetUrl the address the file came from, required
   * @param sha256 the file's SHA-256 as the uploader read it, optional
   * @param consolidatedTo the day the publisher consolidated it to, optional
   * @param user the administrator, recorded on the import
   * @return what was unchanged, stored and requested, and the import rows
   */
  @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public LawWatchReport importFile(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String jurisdiction,
      @RequestParam(required = false) String instrument,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) String datasetUrl,
      @RequestParam(required = false) String sha256,
      @RequestParam(required = false) String consolidatedTo,
      @AuthenticationPrincipal User user) {
    if (!settings.corpusEnabled()) {
      throw new LawReviewProblem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "law-corpus-disabled",
          "The law corpus is switched off (law.corpus.enabled).");
    }
    return LawReviewProblem.translate(
        () ->
            imports.importFile(
                new ManualLawFile(
                    upper(jurisdiction),
                    instrument,
                    language,
                    bytes(file),
                    datasetUrl,
                    user == null ? null : user.id().toString(),
                    sha256 == null || sha256.isBlank() ? null : sha256.trim(),
                    consolidatedTo == null || consolidatedTo.isBlank()
                        ? null
                        : LawReviewController.day(consolidatedTo, "consolidatedTo"))));
  }

  private static String upper(String jurisdiction) {
    if (jurisdiction == null || jurisdiction.isBlank()) {
      throw new IllegalArgumentException("jurisdiction is required");
    }
    return jurisdiction.trim().toUpperCase(Locale.ROOT);
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("the uploaded file could not be read", e);
    }
  }
}
