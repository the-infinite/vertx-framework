package io.github.the_infinite.framework.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatorConstraintsTest {

  private static ValidationException expectFail(final Object dto) {
    return assertThrows(ValidationException.class, () -> Validator.validate(dto));
  }

  // ---- IsUUID ----

  @Test
  void isUuid_valid() {
    final var d = new TestModels.UuidDto();
    d.id = TestModels.VALID_UUID;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isUuid_invalidFormat_fails() {
    final var d = new TestModels.UuidDto();
    d.id = "not-a-uuid";
    expectFail(d);
  }

  @Test
  void isUuid_nonString_fails() {
    final var d = new TestModels.UuidNonStringDto();
    d.id = 123;
    final var ex = expectFail(d);
    assertEquals("id", ex.getField());
  }

  @Test
  void isUuid_nullAllowedWithoutNullable_passes() {
    // A null value is skipped unless @IsNullable(false) is present; see edge-case tests.
    assertDoesNotThrow(() -> Validator.validate(new TestModels.UuidDto()));
  }

  // ---- IsBefore / IsAfter ----

  @Test
  void isBefore_valid() {
    final var d = new TestModels.BeforeDto();
    d.when = "2019-01-01T00:00:00Z";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isBefore_equalBound_fails() {
    final var d = new TestModels.BeforeDto();
    d.when = "2020-01-01T00:00:00Z";
    expectFail(d);
  }

  @Test
  void isBefore_afterBound_fails() {
    final var d = new TestModels.BeforeDto();
    d.when = "2021-01-01T00:00:00Z";
    expectFail(d);
  }

  @Test
  void isBefore_unparseable_fails() {
    final var d = new TestModels.BeforeDto();
    d.when = "not-a-date";
    expectFail(d);
  }

  @Test
  void isAfter_valid() {
    final var d = new TestModels.AfterDto();
    d.when = "2021-01-01T00:00:00Z";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isAfter_equalBound_fails() {
    final var d = new TestModels.AfterDto();
    d.when = "2020-01-01T00:00:00Z";
    expectFail(d);
  }

  @Test
  void isAfter_beforeBound_fails() {
    final var d = new TestModels.AfterDto();
    d.when = "2019-01-01T00:00:00Z";
    expectFail(d);
  }

  // ---- IsMatch (full match) ----

  @Test
  void isMatch_valid() {
    final var d = new TestModels.MatchDto();
    d.code = "ABC";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isMatch_tooLong_fails() {
    final var d = new TestModels.MatchDto();
    d.code = "ABCD";
    expectFail(d);
  }

  @Test
  void isMatch_lowercase_fails() {
    final var d = new TestModels.MatchDto();
    d.code = "abc";
    expectFail(d);
  }

  // ---- IsGreater ----

  @Test
  void isGreater_valid() {
    final var d = new TestModels.GreaterDto();
    d.n = 11L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isGreater_equal_fails() {
    final var d = new TestModels.GreaterDto();
    d.n = 10L;
    expectFail(d);
  }

  @Test
  void isGreater_orEquals_equal_passes() {
    final var d = new TestModels.GreaterOrEqualsDto();
    d.n = 10L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isGreater_nonNumeric_fails() {
    final var d = new TestModels.NumericOnStringDto();
    d.n = "abc";
    expectFail(d);
  }

  @Test
  void isGreater_nullAllowedWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.GreaterDto()));
  }

  // ---- IsLesser ----

  @Test
  void isLesser_valid() {
    final var d = new TestModels.LesserDto();
    d.n = 9L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isLesser_equal_fails() {
    final var d = new TestModels.LesserDto();
    d.n = 10L;
    expectFail(d);
  }

  @Test
  void isLesser_orEquals_equal_passes() {
    final var d = new TestModels.LesserOrEqualsDto();
    d.n = 10L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  // ---- IsDecimalGreater / IsDecimalLesser ----

  @Test
  void isDecimalGreater_valid() {
    final var d = new TestModels.DecimalGreaterDto();
    d.n = 2.0;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isDecimalGreater_equal_fails() {
    final var d = new TestModels.DecimalGreaterDto();
    d.n = 1.5;
    expectFail(d);
  }

  @Test
  void isDecimalGreater_orEquals_equal_passes() {
    final var d = new TestModels.DecimalGreaterOrEqualsDto();
    d.n = 1.5;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isDecimalLesser_valid() {
    final var d = new TestModels.DecimalLesserDto();
    d.n = 1.0;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isDecimalLesser_equal_fails() {
    final var d = new TestModels.DecimalLesserDto();
    d.n = 1.5;
    expectFail(d);
  }

  @Test
  void isDecimalLesser_orEquals_equal_passes() {
    final var d = new TestModels.DecimalLesserOrEqualsDto();
    d.n = 1.5;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  // ---- IsBetween / IsOutside ----

  @Test
  void isBetween_inclusive_endpoints_pass() {
    assertDoesNotThrow(() -> {
      final var lo = new TestModels.BetweenDto();
      lo.n = 1L;
      Validator.validate(lo);
      final var hi = new TestModels.BetweenDto();
      hi.n = 10L;
      Validator.validate(hi);
      final var mid = new TestModels.BetweenDto();
      mid.n = 5L;
      Validator.validate(mid);
    });
  }

  @Test
  void isBetween_outOfRange_fails() {
    final var d = new TestModels.BetweenDto();
    d.n = 11L;
    expectFail(d);
  }

  @Test
  void isBetween_exclusive_endpoints_fail() {
    expectFail(betweenExclusive(1L));
    expectFail(betweenExclusive(10L));
    assertDoesNotThrow(() -> Validator.validate(betweenExclusive(5L)));
  }

  private static TestModels.BetweenExclusiveDto betweenExclusive(final Long n) {
    final var d = new TestModels.BetweenExclusiveDto();
    d.n = n;
    return d;
  }

  @Test
  void isOutside_inclusive_endpoint_fails() {
    expectFail(outside(1L));
    expectFail(outside(10L));
    assertDoesNotThrow(() -> Validator.validate(outside(0L)));
    assertDoesNotThrow(() -> Validator.validate(outside(11L)));
  }

  @Test
  void isOutside_exclusive_endpoint_passes() {
    assertDoesNotThrow(() -> Validator.validate(outsideExclusive(1L)));
    assertDoesNotThrow(() -> Validator.validate(outsideExclusive(10L)));
    expectFail(outsideExclusive(5L));
  }

  private static TestModels.OutsideDto outside(final Long n) {
    final var d = new TestModels.OutsideDto();
    d.n = n;
    return d;
  }

  private static TestModels.OutsideExclusiveDto outsideExclusive(final Long n) {
    final var d = new TestModels.OutsideExclusiveDto();
    d.n = n;
    return d;
  }

  // ---- IsDecimalBetween / IsDecimalOutside ----

  @Test
  void isDecimalBetween_inclusive_passes() {
    final var lo = new TestModels.DecimalBetweenDto();
    lo.n = 1.0;
    final var hi = new TestModels.DecimalBetweenDto();
    hi.n = 10.0;
    final var mid = new TestModels.DecimalBetweenDto();
    mid.n = 5.5;
    assertDoesNotThrow(() -> {
      Validator.validate(lo);
      Validator.validate(hi);
      Validator.validate(mid);
    });
  }

  @Test
  void isDecimalBetween_outOfRange_fails() {
    final var d = new TestModels.DecimalBetweenDto();
    d.n = 10.5;
    expectFail(d);
  }

  @Test
  void isDecimalOutside_mid_fails() {
    final var d = new TestModels.DecimalOutsideDto();
    d.n = 5.0;
    expectFail(d);
  }

  @Test
  void isDecimalOutside_endpoint_passes() {
    final var d = new TestModels.DecimalOutsideDto();
    d.n = 0.0;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  // ---- IsSatisfies ----

  @Test
  void isSatisfies_valid() {
    final var d = new TestModels.SatisfiesDto();
    d.n = 4L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isSatisfies_invalid_fails() {
    final var d = new TestModels.SatisfiesDto();
    d.n = 3L;
    expectFail(d);
  }

  // ---- IsEmail ----

  @Test
  void isEmail_valid() {
    final var d = new TestModels.EmailDto();
    d.email = "a@b.com";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isEmail_invalid_fails() {
    final var d = new TestModels.EmailDto();
    d.email = "not-an-email";
    expectFail(d);
  }

  @Test
  void isEmail_nullAllowedWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.EmailDto()));
  }

  // ---- IsNotEmpty ----

  @Test
  void isNotEmpty_string_valid() {
    final var d = new TestModels.NotEmptyStringDto();
    d.value = "x";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isNotEmpty_string_empty_fails() {
    final var d = new TestModels.NotEmptyStringDto();
    d.value = "";
    expectFail(d);
  }

  @Test
  void isNotEmpty_list_valid() {
    final var d = new TestModels.NotEmptyListDto();
    d.value = java.util.List.of("x");
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isNotEmpty_list_empty_fails() {
    final var d = new TestModels.NotEmptyListDto();
    d.value = java.util.List.of();
    expectFail(d);
  }

  @Test
  void isNotEmpty_nullAllowedWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.NotEmptyStringDto()));
  }

  // ---- IsNotBlank ----

  @Test
  void isNotBlank_valid() {
    final var d = new TestModels.NotBlankDto();
    d.value = "x";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isNotBlank_blank_fails() {
    final var d = new TestModels.NotBlankDto();
    d.value = "   ";
    expectFail(d);
  }

  @Test
  void isNotBlank_nullAllowedWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.NotBlankDto()));
  }

  // ---- IsLength ----

  @Test
  void isLength_valid() {
    final var d = new TestModels.LengthDto();
    d.value = "abc";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isLength_tooShort_fails() {
    final var d = new TestModels.LengthDto();
    d.value = "a";
    expectFail(d);
  }

  @Test
  void isLength_tooLong_fails() {
    final var d = new TestModels.LengthDto();
    d.value = "abcdef";
    expectFail(d);
  }

  // ---- IsSize ----

  @Test
  void isSize_list_valid() {
    final var d = new TestModels.SizeListDto();
    d.value = java.util.List.of("a", "b");
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isSize_list_tooMany_fails() {
    final var d = new TestModels.SizeListDto();
    d.value = java.util.List.of("a", "b", "c", "d");
    expectFail(d);
  }

  @Test
  void isSize_array_valid() {
    final var d = new TestModels.SizeArrayDto();
    d.value = new String[]{"a", "b"};
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isSize_string_valid() {
    final var d = new TestModels.SizeStringDto();
    d.value = "abc";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  // ---- IsIn ----

  @Test
  void isIn_valid() {
    final var d = new TestModels.InDto();
    d.color = "green";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isIn_invalid_fails() {
    final var d = new TestModels.InDto();
    d.color = "yellow";
    expectFail(d);
  }

  @Test
  void isIn_nullAllowedWithoutNullable_passes() {
    assertDoesNotThrow(() -> Validator.validate(new TestModels.InDto()));
  }

  // ---- IsUrl ----

  @Test
  void isUrl_valid() {
    final var d = new TestModels.UrlDto();
    d.value = "https://example.com/path";
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isUrl_noHost_fails() {
    final var d = new TestModels.UrlDto();
    d.value = "ftp:///no-host";
    expectFail(d);
  }

  @Test
  void isUrl_unparseable_fails() {
    final var d = new TestModels.UrlDto();
    d.value = "not a url";
    expectFail(d);
  }

  // ---- IsPositive / IsNegative ----

  @Test
  void isPositive_valid() {
    final var d = new TestModels.PositiveDto();
    d.n = 1L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isPositive_zero_fails() {
    final var d = new TestModels.PositiveDto();
    d.n = 0L;
    expectFail(d);
  }

  @Test
  void isNegative_valid() {
    final var d = new TestModels.NegativeDto();
    d.n = -1L;
    assertDoesNotThrow(() -> Validator.validate(d));
  }

  @Test
  void isNegative_zero_fails() {
    final var d = new TestModels.NegativeDto();
    d.n = 0L;
    expectFail(d);
  }
}
