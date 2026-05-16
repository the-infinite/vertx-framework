package io.github.the_infinite.framework.data.types;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.the_infinite.framework.utils.DataHelpers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@SuppressWarnings("unused")
public record PaginatedResult<T>(
        @NotNull List<T> items,
        int limit,
        long count,
        @Nullable String cursor,
        @Nullable String next
) {

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
