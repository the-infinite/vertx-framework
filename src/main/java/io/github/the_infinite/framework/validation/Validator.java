package io.github.the_infinite.framework.validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class Validator {

  @FunctionalInterface
  private interface Constraint {
    void check(Annotation annotation, Field field, Object value);
  }

  private static final Map<Class<? extends Annotation>, Constraint> CONSTRAINTS = new HashMap<>();

  private static final Pattern EMAIL_PATTERN =
    Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  static {
    register(IsUUID.class, (a, f, v) -> {
      final var ann = (IsUUID) a;
      if (!(v instanceof CharSequence)) {
        fail(f, ann.message(), "expected a string to validate as a UUID but was " + typeName(v));
      }
      try {
        java.util.UUID.fromString(v.toString());
      } catch (final IllegalArgumentException e) {
        fail(f, ann.message(), "expected a valid UUID but was '" + v + "'");
      }
    });

    register(IsBefore.class, (a, f, v) -> {
      final var ann = (IsBefore) a;
      final var bound = parseBound(f, ann.time());
      final var actual = toOffsetDateTime(f, v, ann.message());
      if(actual == null) {
        fail(f, ann.message(), "expected a valid OffsetDateTime but was '" + v + "'");
      }
      if (!actual.isBefore(bound)) {
        fail(f, ann.message(), "expected a date strictly before " + bound + " but was " + actual);
      }
    });

    register(IsAfter.class, (a, f, v) -> {
      final var ann = (IsAfter) a;
      final var bound = parseBound(f, ann.time());
      final var actual = toOffsetDateTime(f, v, ann.message());
      if(actual == null) {
        fail(f, ann.message(), "expected a valid OffsetDateTime but was '" + v + "'");
      }
      if (!actual.isAfter(bound)) {
        fail(f, ann.message(), "expected a date strictly after " + bound + " but was " + actual);
      }
    });

    register(IsMatch.class, (a, f, v) -> {
      final var ann = (IsMatch) a;
      if (!(v instanceof CharSequence)) {
        fail(f, ann.message(), "expected a string to match a pattern but was " + typeName(v));
      }
      if (!Pattern.compile(ann.regex()).matcher(v.toString()).matches()) {
        fail(f, ann.message(), "value '" + v + "' does not match pattern " + ann.regex());
      }
    });

    register(IsGreater.class, (a, f, v) -> {
      final var ann = (IsGreater) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.orEquals() ? n >= ann.value() : n > ann.value();
      if (!ok) {
        fail(f, ann.message(), "expected value >" + (ann.orEquals() ? "=" : "") + " " + ann.value() + " but was " + n);
      }
    });

    register(IsLesser.class, (a, f, v) -> {
      final var ann = (IsLesser) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.orEquals() ? n <= ann.value() : n < ann.value();
      if (!ok) {
        fail(f, ann.message(), "expected value <" + (ann.orEquals() ? "=" : "") + " " + ann.value() + " but was " + n);
      }
    });

    register(IsDecimalGreater.class, (a, f, v) -> {
      final var ann = (IsDecimalGreater) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.orEquals() ? n >= ann.value() : n > ann.value();
      if (!ok) {
        fail(f, ann.message(), "expected value >" + (ann.orEquals() ? "=" : "") + " " + ann.value() + " but was " + n);
      }
    });

    register(IsDecimalLesser.class, (a, f, v) -> {
      final var ann = (IsDecimalLesser) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.orEquals() ? n <= ann.value() : n < ann.value();
      if (!ok) {
        fail(f, ann.message(), "expected value <" + (ann.orEquals() ? "=" : "") + " " + ann.value() + " but was " + n);
      }
    });

    register(IsBetween.class, (a, f, v) -> {
      final var ann = (IsBetween) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.inclusive()
        ? (n >= ann.start() && n <= ann.end())
        : (n > ann.start() && n < ann.end());
      if (!ok) {
        fail(f, ann.message(), "expected value within [" + ann.start() + ", " + ann.end() + "]"
          + (ann.inclusive() ? " inclusive" : " exclusive") + " but was " + n);
      }
    });

    register(IsOutside.class, (a, f, v) -> {
      final var ann = (IsOutside) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.inclusive()
        ? (n < ann.start() || n > ann.end())
        : (n <= ann.start() || n >= ann.end());
      if (!ok) {
        fail(f, ann.message(), "expected value outside [" + ann.start() + ", " + ann.end() + "]"
          + (ann.inclusive() ? " inclusive" : " exclusive") + " but was " + n);
      }
    });

    register(IsDecimalBetween.class, (a, f, v) -> {
      final var ann = (IsDecimalBetween) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.inclusive()
        ? (n >= ann.start() && n <= ann.end())
        : (n > ann.start() && n < ann.end());
      if (!ok) {
        fail(f, ann.message(), "expected value within [" + ann.start() + ", " + ann.end() + "]"
          + (ann.inclusive() ? " inclusive" : " exclusive") + " but was " + n);
      }
    });

    register(IsDecimalOutside.class, (a, f, v) -> {
      final var ann = (IsDecimalOutside) a;
      final var n = toNumber(f, v, ann.message());
      final var ok = ann.inclusive()
        ? (n < ann.start() || n > ann.end())
        : (n <= ann.start() || n >= ann.end());
      if (!ok) {
        fail(f, ann.message(), "expected value outside [" + ann.start() + ", " + ann.end() + "]"
          + (ann.inclusive() ? " inclusive" : " exclusive") + " but was " + n);
      }
    });

    register(IsSatisfies.class, (a, f, v) -> {
      final var ann = (IsSatisfies) a;
      try {
        final var predicate = ann.value().getDeclaredConstructor().newInstance();
        if (!predicate.test(v)) {
          fail(f, ann.message(), "value did not satisfy custom predicate " + ann.value().getName());
        }
      } catch (final ReflectiveOperationException e) {
        throw new ValidationException(f.getName(), "could not instantiate predicate " + ann.value().getName(), e);
      }
    });

    register(IsEmail.class, (a, f, v) -> {
      final var ann = (IsEmail) a;
      if (!(v instanceof CharSequence)) {
        fail(f, ann.message(), "expected a string email but was " + typeName(v));
      }
      if (!EMAIL_PATTERN.matcher(v.toString()).matches()) {
        fail(f, ann.message(), "expected a valid email but was '" + v + "'");
      }
    });

    register(IsNotEmpty.class, (a, f, v) -> {
      final var ann = (IsNotEmpty) a;
      if (isEmpty(v)) {
        fail(f, ann.message(), "expected a non-empty value but it was empty");
      }
    });

    register(IsNotBlank.class, (a, f, v) -> {
      final var ann = (IsNotBlank) a;
      if (!(v instanceof CharSequence cs) || cs.toString().isBlank()) {
        fail(f, ann.message(), "expected a non-blank string but was '" + v + "'");
      }
    });

    register(IsLength.class, (a, f, v) -> {
      final var ann = (IsLength) a;
      if (!(v instanceof CharSequence)) {
        fail(f, ann.message(), "expected a string to measure length but was " + typeName(v));
      }
      final var len = ((CharSequence) v).length();
      if (len < ann.min() || len > ann.max()) {
        fail(f, ann.message(), "length " + len + " not within [" + ann.min() + ", " + ann.max() + "]");
      }
    });

    register(IsSize.class, (a, f, v) -> {
      final var ann = (IsSize) a;
      final var size = sizeOf(v);
      if (size < 0) {
        fail(f, ann.message(), "expected a countable (string/collection/map/array) value but was " + typeName(v));
      }
      if (size < ann.min() || size > ann.max()) {
        fail(f, ann.message(), "size " + size + " not within [" + ann.min() + ", " + ann.max() + "]");
      }
    });

    register(IsIn.class, (a, f, v) -> {
      final var ann = (IsIn) a;
      if (v == null) {
        fail(f, ann.message(), "value is null and not in allowed set");
      }
      final var s = v.toString();
      for (final String allowed : ann.value()) {
        if (allowed.equals(s)) {
          return;
        }
      }
      fail(f, ann.message(), "value '" + s + "' is not one of " + Arrays.toString(ann.value()));
    });

    register(IsUrl.class, (a, f, v) -> {
      final var ann = (IsUrl) a;
      if (!(v instanceof CharSequence)) {
        fail(f, ann.message(), "expected a string URL but was " + typeName(v));
      }
      try {
        final var uri = URI.create(v.toString());
        if (uri.getScheme() == null || uri.getHost() == null) {
          fail(f, ann.message(), "expected a valid URL but was '" + v + "'");
        }
      } catch (final IllegalArgumentException e) {
        fail(f, ann.message(), "expected a valid URL but was '" + v + "'");
      }
    });

    register(IsPositive.class, (a, f, v) -> {
      final var ann = (IsPositive) a;
      final var n = toNumber(f, v, ann.message());
      if (!(n > 0)) {
        fail(f, ann.message(), "expected a positive value but was " + n);
      }
    });

    register(IsNegative.class, (a, f, v) -> {
      final var ann = (IsNegative) a;
      final var n = toNumber(f, v, ann.message());
      if (!(n < 0)) {
        fail(f, ann.message(), "expected a negative value but was " + n);
      }
    });
  }

  private Validator() {
  }

  public static void validate(final Object target) {
    if (target == null) {
      throw new ValidationException("target", "cannot validate a null object");
    }
    for (var type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      for (final Field field : type.getDeclaredFields()) {
        if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        final var annotations = field.getDeclaredAnnotations();
        var hasConstraint = false;
        for (final var ann : annotations) {
          if (CONSTRAINTS.containsKey(ann.annotationType()) || ann instanceof IsNullable) {
            hasConstraint = true;
            break;
          }
        }
        if (!hasConstraint) {
          continue;
        }

        field.setAccessible(true);
        final Object value;
        try {
          value = field.get(target);
        } catch (final IllegalAccessException e) {
          throw new ValidationException(field.getName(), "unable to read field", e);
        }

        final var nullable = field.getAnnotation(IsNullable.class);
        if (value == null) {
          if (nullable != null && !nullable.value()) {
            fail(field, nullable.message(), "must not be null");
          }
          continue;
        }

        for (final var ann : annotations) {
          final var constraint = CONSTRAINTS.get(ann.annotationType());
          if (constraint != null) {
            constraint.check(ann, field, value);
          }
        }
      }
    }
  }

  private static void register(final Class<? extends Annotation> type, final Constraint constraint) {
    CONSTRAINTS.put(type, constraint);
  }

  private static void fail(final Field field, final String custom, final String defaultMessage) {
    final var message = (custom != null && !custom.isBlank()) ? custom : defaultMessage;
    throw new ValidationException(field.getName(), message);
  }

  private static String typeName(final Object value) {
    return value == null ? "null" : value.getClass().getName();
  }

  private static double toNumber(final Field field, final Object value, final String custom) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    fail(field, custom, "expected a numeric value but was " + typeName(value));
    return 0;
  }

  private static OffsetDateTime toOffsetDateTime(final Field field, final Object value, final String custom) {
    if (value instanceof OffsetDateTime o) {
      return o;
    }
    if (value instanceof Instant i) {
      return i.atOffset(ZoneOffset.UTC);
    }
    if (value instanceof ZonedDateTime z) {
      return z.toOffsetDateTime();
    }
    if (value instanceof LocalDateTime l) {
      return l.atOffset(ZoneOffset.UTC);
    }
    if (value instanceof CharSequence s) {
      try {
        return OffsetDateTime.parse(s.toString());
      } catch (final DateTimeParseException e) {
        fail(field, custom, "expected an ISO date-time but was '" + s + "'");
      }
    }
    fail(field, custom, "expected a date-time value but was " + typeName(value));
    return null;
  }

  private static OffsetDateTime parseBound(final Field field, final String iso) {
    try {
      return OffsetDateTime.parse(iso);
    } catch (final DateTimeParseException e) {
      throw new ValidationException(field.getName(), "invalid date bound '" + iso + "': " + e.getMessage());
    }
  }

  private static boolean isEmpty(final Object value) {
    switch (value) {
      case null -> {
        return true;
      }
      case CharSequence cs -> {
        return cs.isEmpty();
      }
      case Collection<?> c -> {
        return c.isEmpty();
      }
      case Map<?, ?> m -> {
        return m.isEmpty();
      }
      default -> {
      }
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value) == 0;
    }
    return false;
  }

  private static int sizeOf(final Object value) {
    if (value instanceof CharSequence cs) {
      return cs.length();
    }
    if (value instanceof Collection<?> c) {
      return c.size();
    }
    if (value instanceof Map<?, ?> m) {
      return m.size();
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value);
    }
    return -1;
  }
}
