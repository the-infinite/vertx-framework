package io.github.the_infinite.framework.validation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared DTOs used across the validator test-suite. All annotation types live in the same package,
 * so no imports are required.
 */
public final class TestModels {

  private TestModels() {
  }

  public static final String VALID_UUID = "123e4567-e89b-12d3-a456-426614174000";

  // ---- single constraint DTOs ----

  public static class UuidDto {
    @IsUUID
    public String id;
  }

  public static class UuidNonStringDto {
    @IsUUID
    public Object id;
  }

  public static class BeforeDto {
    @IsBefore(time = "2020-01-01T00:00:00Z")
    public String when;
  }

  public static class AfterDto {
    @IsAfter(time = "2020-01-01T00:00:00Z")
    public String when;
  }

  public static class MatchDto {
    @IsMatch(regex = "[A-Z]{3}")
    public String code;
  }

  public static class GreaterDto {
    @IsGreater(value = 10)
    public Long n;
  }

  public static class GreaterOrEqualsDto {
    @IsGreater(value = 10, orEquals = true)
    public Long n;
  }

  public static class LesserDto {
    @IsLesser(value = 10)
    public Long n;
  }

  public static class LesserOrEqualsDto {
    @IsLesser(value = 10, orEquals = true)
    public Long n;
  }

  public static class DecimalGreaterDto {
    @IsDecimalGreater(value = 1.5)
    public Double n;
  }

  public static class DecimalGreaterOrEqualsDto {
    @IsDecimalGreater(value = 1.5, orEquals = true)
    public Double n;
  }

  public static class DecimalLesserDto {
    @IsDecimalLesser(value = 1.5)
    public Double n;
  }

  public static class DecimalLesserOrEqualsDto {
    @IsDecimalLesser(value = 1.5, orEquals = true)
    public Double n;
  }

  public static class BetweenDto {
    @IsBetween(start = 1, end = 10)
    public Long n;
  }

  public static class BetweenExclusiveDto {
    @IsBetween(start = 1, end = 10, inclusive = false)
    public Long n;
  }

  public static class OutsideDto {
    @IsOutside(start = 1, end = 10)
    public Long n;
  }

  public static class OutsideExclusiveDto {
    @IsOutside(start = 1, end = 10, inclusive = false)
    public Long n;
  }

  public static class DecimalBetweenDto {
    @IsDecimalBetween(start = 1.0, end = 10.0)
    public Double n;
  }

  public static class DecimalOutsideDto {
    @IsDecimalOutside(start = 1.0, end = 10.0)
    public Double n;
  }

  public static class SatisfiesDto {
    @IsSatisfies(EvenPredicate.class)
    public Long n;
  }

  public static class EvenPredicate implements Predicate<Object> {
    public EvenPredicate() {
    }

    @Override
    public boolean test(final Object o) {
      return o instanceof Number n && n.longValue() % 2 == 0;
    }
  }

  public static class EmailDto {
    @IsEmail
    public String email;
  }

  public static class NotEmptyStringDto {
    @IsNotEmpty
    public String value;
  }

  public static class NotEmptyListDto {
    @IsNotEmpty
    public List<String> value;
  }

  public static class NotBlankDto {
    @IsNotBlank
    public String value;
  }

  public static class LengthDto {
    @IsLength(min = 2, max = 5)
    public String value;
  }

  public static class SizeListDto {
    @IsSize(min = 1, max = 3)
    public List<String> value;
  }

  public static class SizeArrayDto {
    @IsSize(min = 1, max = 3)
    public String[] value;
  }

  public static class SizeStringDto {
    @IsSize(min = 2, max = 4)
    public String value;
  }

  public static class InDto {
    @IsIn({"red", "green", "blue"})
    public String color;
  }

  public static class UrlDto {
    @IsUrl
    public String value;
  }

  public static class PositiveDto {
    @IsPositive
    public Long n;
  }

  public static class NegativeDto {
    @IsNegative
    public Long n;
  }

  public static class CustomMessageDto {
    @IsGreater(value = 10, message = "must be big")
    public Long n;
  }

  public static class NumericOnStringDto {
    @IsGreater(value = 10)
    public String n;
  }

  // ---- combinator DTOs ----

  public static class OrDto {
    @CombineOr
    @IsGreater(value = 100)
    @IsLesser(value = 0)
    public Long n;
  }

  public static class OrNotDto {
    @CombineOr
    @CombineNot
    @IsGreater(value = 100)
    @IsLesser(value = 0)
    public Long n;
  }

  public static class AndDto {
    @CombineAnd
    @IsGreater(value = 0)
    @IsLesser(value = 10)
    public Long n;
  }

  public static class NotDto {
    @CombineNot
    @IsPositive
    public Long n;
  }

  public static class OrSingleDto {
    @CombineOr
    @IsPositive
    public Long n;
  }

  public static class NotSingleDto {
    @CombineNot
    @IsPositive
    public Long n;
  }

  // ---- null handling ----

  public static class NullableFalseDto {
    @IsNullable(false)
    public String value;
  }

  public static class NullableFalseUuidDto {
    @IsNullable(false)
    @IsUUID
    public String id;
  }

  public static class NullableTrueDto {
    @IsNullable(true)
    public String value;
  }

  public static class NoNullableDto {
    public String value;
  }

  // ---- stacking ----

  public static class StackedDto {
    @IsPositive
    @IsLesser(value = 10)
    public Long n;
  }

  // ---- recursion ----

  public static class Inner {
    @IsUUID
    public String id;

    @IsGreater(value = 0)
    public Long count;

    public Inner() {
    }

    public Inner(final String id, final Long count) {
      this.id = id;
      this.count = count;
    }
  }

  public static class Outer {
    @IsNullable(false)
    public Inner inner;

    public List<Inner> items;

    public Inner[] arr;

    public Map<String, Inner> map;

    public Optional<Inner> maybe;

    public String name;

    public Outer() {
    }

    public Outer(final Inner inner) {
      this.inner = inner;
    }
  }

  public static class Node {
    @IsNotBlank
    public String label;

    @IsNullable(true)
    public Node next;

    public Node() {
    }

    public Node(final String label) {
      this.label = label;
    }
  }

  public static Inner validInner() {
    return new Inner(VALID_UUID, 1L);
  }

  public static Outer outerWithValidInner() {
    return new Outer(validInner());
  }
}
