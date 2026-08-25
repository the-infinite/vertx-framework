package io.github.the_infinite.framework.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatorRecursionTest {

  private static ValidationException expectFail(final Object dto) {
    return assertThrows(ValidationException.class, () -> Validator.validate(dto));
  }

  // ---- nested single DTO ----

  @Test
  void nested_single_invalidField_throwsOnInnerField() {
    final var o = TestModels.outerWithValidInner();
    o.inner = new TestModels.Inner("bad-uuid", 1L);
    final var ex = expectFail(o);
    assertEquals("id", ex.getField());
  }

  @Test
  void nested_single_valid_passes() {
    final var o = TestModels.outerWithValidInner();
    assertDoesNotThrow(() -> Validator.validate(o));
  }

  @Test
  void nested_nullableFalse_null_throws() {
    final var o = new TestModels.Outer(); // inner is null
    final var ex = expectFail(o);
    assertEquals("inner", ex.getField());
  }

  // ---- collection ----

  @Test
  void nested_collection_invalidElement_throws() {
    final var o = TestModels.outerWithValidInner();
    o.items = List.of(TestModels.validInner(), new TestModels.Inner("bad-uuid", 1L));
    final var ex = expectFail(o);
    assertEquals("id", ex.getField());
  }

  @Test
  void nested_collection_valid_passes() {
    final var o = TestModels.outerWithValidInner();
    o.items = List.of(TestModels.validInner(), TestModels.validInner());
    assertDoesNotThrow(() -> Validator.validate(o));
  }

  // ---- array ----

  @Test
  void nested_array_invalidElement_throws() {
    final var o = TestModels.outerWithValidInner();
    o.arr = new TestModels.Inner[]{new TestModels.Inner("bad-uuid", 1L)};
    final var ex = expectFail(o);
    assertEquals("id", ex.getField());
  }

  // ---- map ----

  @Test
  void nested_map_invalidValue_throws() {
    final var o = TestModels.outerWithValidInner();
    o.map = Map.of("k", new TestModels.Inner("bad-uuid", 1L));
    final var ex = expectFail(o);
    assertEquals("id", ex.getField());
  }

  // ---- optional ----

  @Test
  void nested_optional_invalid_throws() {
    final var o = TestModels.outerWithValidInner();
    o.maybe = Optional.of(new TestModels.Inner("bad-uuid", 1L));
    final var ex = expectFail(o);
    assertEquals("id", ex.getField());
  }

  @Test
  void nested_optional_valid_passes() {
    final var o = TestModels.outerWithValidInner();
    o.maybe = Optional.of(TestModels.validInner());
    assertDoesNotThrow(() -> Validator.validate(o));
  }

  @Test
  void nested_optional_empty_passes() {
    final var o = TestModels.outerWithValidInner();
    o.maybe = Optional.empty();
    assertDoesNotThrow(() -> Validator.validate(o));
  }

  // ---- self-referential cycle (no infinite loop) ----

  @Test
  void cycle_selfReference_valid_passes() {
    final var a = new TestModels.Node("a");
    a.next = a;
    assertDoesNotThrow(() -> Validator.validate(a));
  }

  @Test
  void cycle_twoNodes_valid_passes() {
    final var a = new TestModels.Node("a");
    final var b = new TestModels.Node("b");
    a.next = b;
    b.next = a;
    assertDoesNotThrow(() -> Validator.validate(a));
  }

  @Test
  void cycle_invalidRoot_throws() {
    final var c = new TestModels.Node("");
    final var d = new TestModels.Node("d");
    c.next = d;
    d.next = c;
    final var ex = expectFail(c);
    assertEquals("label", ex.getField());
  }

  // ---- deep valid graph ----

  @Test
  void deep_valid_passes() {
    final var o = TestModels.outerWithValidInner();
    o.items = List.of(TestModels.validInner());
    o.arr = new TestModels.Inner[]{TestModels.validInner()};
    o.map = Map.of("k", TestModels.validInner());
    o.maybe = Optional.of(TestModels.validInner());
    o.name = "ok";
    assertDoesNotThrow(() -> Validator.validate(o));
  }
}
