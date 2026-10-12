package com.krizaka.orazaka.conversationservice.domain.model.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cuts a stream of model tokens into sentences, so each can be spoken as soon as it is complete
 * (ADR-076).
 *
 * <p>The cut is on <b>strong punctuation followed by a space</b> — {@code . ! ? …} and a line break
 * — and nowhere else: a comma is a breath, not a sentence, and cutting there would send the speech
 * engine fragments whose intonation is wrong. Three cases keep a period from ending a sentence,
 * because each one produced a broken clip when it did: an abbreviation ({@code M.}, {@code etc.}),
 * a number ({@code 9.8}) and a sentence too short to stand alone, which is held and spoken with the
 * next one.
 *
 * <p>Not thread-safe: one buffer per turn, fed by the one thread that reads the model's stream.
 */
public final class SentenceBuffer {

  /**
   * Shorter than this, a "sentence" is held and joined to the next: "Oui." alone sounds clipped.
   */
  static final int MINIMUM_LENGTH = 12;

  private static final Set<Character> ENDS = Set.of('.', '!', '?', '…');
  private static final Set<Character> CLOSERS = Set.of('»', '"', '\'', ')', '”', '’');
  private static final Set<String> ABBREVIATIONS =
      Set.of(
          "m", "mme", "mmes", "mlle", "dr", "st", "ste", "etc", "ex", "cf", "mr", "mrs", "ms", "vs",
          "e.g", "i.e", "p", "no");

  private final StringBuilder pending = new StringBuilder();

  /**
   * Adds a delta and returns the sentences it completed, in order.
   *
   * @param delta the next piece of the model's answer; {@code null} adds nothing
   * @return the sentences that are now complete, possibly none
   */
  public List<String> accept(String delta) {
    if (delta == null || delta.isEmpty()) {
      return List.of();
    }
    pending.append(delta);
    List<String> sentences = new ArrayList<>();
    int start = 0;
    int i = 0;
    while (i < pending.length()) {
      int end = boundaryAt(i);
      if (end > 0) {
        String sentence = pending.substring(start, end).strip();
        if (sentence.length() >= MINIMUM_LENGTH) {
          sentences.add(sentence);
          start = end;
        }
        i = end;
      } else {
        i++;
      }
    }
    pending.delete(0, start);
    return sentences;
  }

  /**
   * What is left once the stream has ended — the last sentence, which often has no space after its
   * period.
   *
   * @return the remainder, or empty when nothing but whitespace is left
   */
  public Optional<String> flush() {
    String rest = pending.toString().strip();
    pending.setLength(0);
    return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
  }

  /**
   * The index just after a sentence that ends at {@code i}, or {@code -1}.
   *
   * <p>A boundary needs what follows it: until the next character arrives a period may still be a
   * decimal point, so the last character of the buffer is never a boundary — {@link #flush} takes
   * it.
   */
  private int boundaryAt(int i) {
    char c = pending.charAt(i);
    if (c == '\n') {
      return i + 1;
    }
    if (!ENDS.contains(c)) {
      return -1;
    }
    int j = i + 1;
    while (j < pending.length()
        && (ENDS.contains(pending.charAt(j)) || CLOSERS.contains(pending.charAt(j)))) {
      j++;
    }
    if (j >= pending.length() || !Character.isWhitespace(pending.charAt(j))) {
      return -1;
    }
    // French typography puts a space before a closing guillemet: "… plus… » ce soir" ends the
    // quotation, not the sentence, at the ellipsis.
    if (j + 1 < pending.length()
        && (pending.charAt(j + 1) == '»' || pending.charAt(j + 1) == '”')) {
      j += 2;
      if (j >= pending.length() || !Character.isWhitespace(pending.charAt(j))) {
        return -1;
      }
      return isSentenceEnd(j) ? j : -1;
    }
    if (c == '.' && (isAbbreviation(i) || isNumber(i, j))) {
      return -1;
    }
    return j;
  }

  /**
   * After a closing quotation, the sentence goes on unless what follows starts a new one: a capital
   * letter or the end of the buffer's known text.
   */
  private boolean isSentenceEnd(int afterSpace) {
    int next = afterSpace;
    while (next < pending.length() && Character.isWhitespace(pending.charAt(next))) {
      next++;
    }
    return next < pending.length() && Character.isUpperCase(pending.charAt(next));
  }

  private boolean isAbbreviation(int period) {
    int k = period - 1;
    while (k >= 0 && (Character.isLetter(pending.charAt(k)) || pending.charAt(k) == '.')) {
      k--;
    }
    String word = pending.substring(k + 1, period).toLowerCase(Locale.ROOT);
    return !word.isEmpty() && ABBREVIATIONS.contains(word);
  }

  private boolean isNumber(int period, int afterSpace) {
    boolean digitBefore = period > 0 && Character.isDigit(pending.charAt(period - 1));
    int next = afterSpace;
    while (next < pending.length() && Character.isWhitespace(pending.charAt(next))) {
      next++;
    }
    return digitBefore && next < pending.length() && Character.isDigit(pending.charAt(next));
  }
}
