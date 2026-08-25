package io.github.the_infinite.framework.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorCombinatorsTest {

  private static ValidationException expectFail(final Object dto) {
    return assertThrows(ValidationException.class, () -> Validator.validate(dto));
  }

  // ---- CombineOr ----

  @Test
  void combineOr_firstSatisfied_passes() {
    final var d = new TestModels.OrDto();
    d.n = 150L; // satisfies @IsGreater(100)
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineOr_secondSatisfied_passes() {
    final var d = new TestModels.OrDto();
    d.n = -5L; // satisfies @IsLesser(0)
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineOr_noneSatisfied_fails() {
    final var d = new TestModels.OrDto();
    d.n = 5L; // satisfies neither
    final var ex = expectFail(d);
    assertTrue(ex.getMessage().contains("combined constraints"), ex.getMessage());
  }

  // ---- CombineOr + CombineNot ----

  @Test
  void combineOrNot_neitherSatisfied_passes() {
    final var d = new TestModels.OrNotDto();
    d.n = 5L; // neither >=100 nor <0 -> OR fails -> NOT passes
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineOrNot_oneSatisfied_fails() {
    final var d = new TestModels.OrNotDto();
    d.n = 150L; // OR satisfied -> NOT negates -> fail
    expectFail(d);
  }

  // ---- CombineAnd (explicit) ----

  @Test
  void combineAnd_bothSatisfied_passes() {
    final var d = new TestModels.AndDto();
    d.n = 5L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineAnd_oneFails_fails() {
    final var d = new TestModels.AndDto();
    d.n = 15L; // @IsLesser(10) fails
    expectFail(d);
  }

  @Test
  void combineAnd_otherFails_fails() {
    final var d = new TestModels.AndDto();
    d.n = -1L; // @IsGreater(0) fails
    expectFail(d);
  }

  // ---- CombineNot ----

  @Test
  void combineNot_constraintFails_passes() {
    final var d = new TestModels.NotDto();
    d.n = -3L; // @IsPositive fails -> NOT passes
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineNot_constraintPasses_fails() {
    final var d = new TestModels.NotDto();
    d.n = 3L; // @IsPositive passes -> NOT negates -> fail
    expectFail(d);
  }

  // ---- single constraint with combinator flags ----

  @Test
  void combineOr_single_passesWhenSatisfied() {
    final var d = new TestModels.OrSingleDto();
    d.n = 5L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineOr_single_failsWhenNotSatisfied() {
    final var d = new TestModels.OrSingleDto();
    d.n = -1L;
    expectFail(d);
  }

  @Test
  void combineNot_single_passesWhenNotSatisfied() {
    final var d = new TestModels.NotSingleDto();
    d.n = -1L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void combineNot_single_failsWhenSatisfied() {
    final var d = new TestModels.NotSingleDto();
    d.n = 1L;
    expectFail(d);
  }
}
