package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A check a product asks for ({@code POST /api/v1/checks/{purpose}}).
 *
 * @param subjectId the opaque id of the subject on the product's side
 * @param lastName the subject's last name
 * @param firstName the subject's first name
 * @param birthDate the subject's birth date, optional
 * @param address the subject's address, optional
 * @param region the subject's region (for example {@code QC}), optional
 * @param runId the product's run the check belongs to, optional
 * @param consented the product's statement that the subject consented to this purpose; without it
 *     the check is refused {@code NO_CONSENT}
 */
public record SubmitCheckRequest(
    String subjectId,
    String lastName,
    String firstName,
    LocalDate birthDate,
    String address,
    String region,
    UUID runId,
    boolean consented) {}
