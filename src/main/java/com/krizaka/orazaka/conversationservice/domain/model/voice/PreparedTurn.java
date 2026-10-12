package com.krizaka.orazaka.conversationservice.domain.model.voice;

import com.krizaka.orazaka.core.domain.model.Context;
import com.krizaka.orazaka.persistence.domain.model.SpeechVoice;
import com.krizaka.orazaka.studio.domain.model.TurnDeclarations;

/**
 * A turn that passed every gate and holds its reservation.
 *
 * @param turn the request
 * @param declarations the pack's declarations, or {@code null} outside an installation
 * @param voice the voice that will speak
 * @param base the actor's context
 * @param turnId the id the chat leg declares, to find its usage
 * @param holdId the reservation, or {@code null} when billing is off
 * @param startedNanos when the turn was received — the zero of every timing
 */
public record PreparedTurn(
    VoiceTurn turn,
    TurnDeclarations declarations,
    SpeechVoice voice,
    Context base,
    String turnId,
    String holdId,
    long startedNanos) {}
