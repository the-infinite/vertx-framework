package io.github.the_infinite.framework.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatorEdgeCasesTest {

  // inline DTOs for fail-fast and combinator custom message scenarios
  static class TwoFieldDto {
    @IsPositive
    public Long a;

    @IsPositive
    public Long b;
  }

  static class OrCustomMsgDto {
    @CombineOr(message = "pick one")
    @IsGreater(value = 100)
    @IsLesser(value = 0)
    public Long n;
  }

  @Test
  void validate_nullTarget_throwsWithTargetField() {
    final var ex = assertThrows(ValidationException.class, () -> Validator.validate(null));
    assertEquals("target", ex.getField());
  }

  @Test
  void validationException_isRuntimeException() {
    final var ex = assertThrows(ValidationException.class, () -> Validator.validate(null));
    assertEquals(RuntimeException.class, ex.getClass().getSuperclass());
  }

  @Test
  void customMessage_isUsed() {
    final var d = new TestModels.CustomMessageDto();
    d.n = 5L;
    final var ex = assertThrows(ValidationException.class, () -> Validator.validate(d));
    assertEquals("must be big", ex.getMessage());
  }

  @Test
  void combinatorCustomMessage_isUsed() {
    final var d = new OrCustomMsgDto();
    d.n = 5L;
    final var ex = assertThrows(ValidationException.class, () -> Validator.validate(d));
    assertEquals("pick one", ex.getMessage());
  }

  @Test
  void stacking_bothSatisfied_passes() {
    final var d = new TestModels.StackedDto();
    d.n = 5L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void stacking_firstFails_fails() {
    final var d = new TestModels.StackedDto();
    d.n = -1L; // @IsPositive fails
    assertThrows(ValidationException.class, () -> Validator.validate(d));
  }

  @Test
  void stacking_secondFails_fails() {
    final var d = new TestModels.StackedDto();
    d.n = 15L; // @IsLesser(10) fails
    assertThrows(ValidationException.class, () -> Validator.validate(d));
  }

  @Test
  void failFast_throwsSingleException() {
    final var d = new TwoFieldDto();
    d.a = -1L;
    d.b = -2L;
    final var ex = assertThrows(ValidationException.class, () -> Validator.validate(d));
    assertEquals("a", ex.getField());
  }

  @Test
  void nullFieldWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.NoNullableDto()));
  }

  @Test
  void nullableTrue_null_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.NullableTrueDto()));
  }

  @Test
  void nullableFalse_null_fails() {
    final var ex = assertThrows(ValidationException.class,
      () -> Validator.validate(new TestModels.NullableFalseDto()));
    assertEquals("value", ex.getField());
  }

  @Test
  void nullableFalse_uuid_null_fails() {
    final var ex = assertThrows(ValidationException.class,
      () -> Validator.validate(new TestModels.NullableFalseUuidDto()));
    assertEquals("id", ex.getField());
  }

  @Test
  void nullableFalse_uuid_valid_passes() {
    final var d = new TestModels.NullableFalseUuidDto();
    d.id = TestModels.VALID_UUID;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void nullableFalse_uuid_invalid_fails() {
    final var d = new TestModels.NullableFalseUuidDto();
    d.id = "bad";
    assertThrows(ValidationException.class, () -> Validator.validate(d));
  }
}
