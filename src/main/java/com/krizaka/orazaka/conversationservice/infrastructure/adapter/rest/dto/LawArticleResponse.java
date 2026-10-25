package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import com.krizaka.orazaka.tools.domain.model.law.LawArticleRead;
import com.krizaka.orazaka.tools.domain.model.law.StatuteVersion;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Answer of {@code GET /api/v1/law/articles} (issue #89): the article in force on the day asked.
 *
 * @param text the normalised text (label and subsections; the historical note is not part of it)
 * @param contentHash the SHA-256 of the text, checked against the corpus on this read
 * @param inForceFrom the day this text took effect
 * @param officialUrl the publisher's page, the address a citation shows
 * @param importId the {@code law_import} that brought this text in
 * @param quote {@code VERIFIED} or {@code NOT_IN_ARTICLE} when a quote was asked, else null
 */
public record LawArticleResponse(
    String text,
    String contentHash,
    LocalDate inForceFrom,
    String officialUrl,
    UUID importId,
    String quote) {

  /**
   * The body of a read that found its article.
   *
   * @param read a {@code FOUND} read
   * @return the body
   */
  public static LawArticleResponse of(LawArticleRead read) {
    StatuteVersion version = read.version();
    return new LawArticleResponse(
        version.text(),
        version.contentHash(),
        version.inForceFrom(),
        version.officialUrl(),
        version.importId(),
        read.quote() == null ? null : read.quote().name());
  }
}
