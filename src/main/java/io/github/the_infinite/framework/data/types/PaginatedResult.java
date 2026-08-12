package io.github.the_infinite.framework.data.types;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

import io.github.the_infinite.framework.utils.DataHelpers;

@SuppressWarnings("unused")
public record PaginatedResult<T>(
  List<T> items,
  Integer limit,
  Long count,
  String cursor,
  String next
) {

  public static <O> PaginatedResult<O> of(Collection<O> items, int limit) {
    return new PaginatedResult<>(
      List.copyOf(items),
      limit,
      (long) items.size(),
      null,
      null
    );
  }

  public static <O> PaginatedResult<O> of(Collection<O> items) {
    return of(items, 30);
  }

  @Override
  public @NotNull String toString() {
    try {
      return DataHelpers.serializeObject(this);
    } catch (JsonProcessingException e) {
      return "PaginatedResult{items=%s, limit=%d, count=%d, cursor=%s, next=%s}".formatted(
        items.stream().map(Object::toString).reduce("",
          (s1, s2) -> s1 + ", " + s2),
        limit,
        count,
        cursor,
        next
      );
    }
  }

  public <O> PaginatedResult<O> mapItems(@NotNull java.util.function.Function<T, O> mapper) {
    List<O> mappedItems = items.stream().map(mapper).toList();
    return new PaginatedResult<>(mappedItems, limit, count, cursor, next);
  }
}
