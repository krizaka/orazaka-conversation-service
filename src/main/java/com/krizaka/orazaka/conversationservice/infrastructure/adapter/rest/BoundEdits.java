package com.krizaka.orazaka.conversationservice.infrastructure.adapter.rest;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.springframework.http.ResponseEntity;

/**
 * What the admin bound controllers share: the answer of a PUT and of a DELETE on a feature's own
 * row.
 */
final class BoundEdits {

  private BoundEdits() {}

  /**
   * Runs a removal and answers it.
   *
   * @param removal removes the row and tells whether there was one; throws {@link
   *     IllegalArgumentException} for the default row
   * @return 204 when a row was removed, 404 when the feature had none
   * @throws InvalidRequestProblem when the removal refuses the key (400)
   */
  static ResponseEntity<Void> respond(BooleanSupplier removal) {
    try {
      return removal.getAsBoolean()
          ? ResponseEntity.noContent().build()
          : ResponseEntity.notFound().build();
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestProblem(e.getMessage());
    }
  }

  /**
   * Runs a write of a bound and turns a refused key or value into a 400.
   *
   * @param value the requested bound, {@code null} when the body lacked it
   * @param field the body's field name, for the message
   * @param write stores the bound; throws {@link IllegalArgumentException} for a refused key or
   *     range
   * @return the stored value
   * @throws InvalidRequestProblem when the value is missing or refused (400)
   */
  static int write(Integer value, String field, IntConsumer write) {
    if (value == null) {
      throw new InvalidRequestProblem(field + " is required");
    }
    try {
      write.accept(value);
      return value;
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestProblem(e.getMessage());
    }
  }
}
