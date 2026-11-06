package com.krizaka.sovkora.conversationservice.domain.model.voice;

import com.krizaka.sovkora.core.domain.model.Context;
import com.krizaka.sovkora.persistence.domain.model.SpeechVoice;
import com.krizaka.sovkora.studio.domain.model.TurnDeclarations;

/**
 * A turn that passed every gate and holds its reservation.
 *
 * @param turn the request
 * @param declarations the pack's declarations, or {@code null} outside an installation
 * @param voice the voice that will speak, or {@code null} when the plan answers in text only
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
