package io.github.the_infinite.framework.logging.monitor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

import io.vertx.core.json.JsonObject;

@SuppressWarnings("unused")
public final class LogEvent<T> {
    private final @NotNull String message;
    private final @NotNull String group;
    private final @Nullable String topic;
    private final @Nullable Map<String, T> data;

    private LogEvent(@NotNull String message, @NotNull String group, @Nullable String topic,
                     @Nullable Map<String, T> data) {
        this.message = message;
        this.group = group;
        this.topic = topic;
        this.data = data;
    }

    private LogEvent(String message, String group, Map<String, T> data) {
        this(message, group, null, data);
    }

    public static <T> LogEvent<T> create(@NotNull String message, @NotNull String group, @Nullable String topic, @Nullable Map<String, T> data) {
        return new LogEvent<>(message, group, topic, data);
    }

    public static <T> LogEvent<T> create(@NotNull String message, @NotNull String group, @Nullable Map<String, T> data) {
        return create(message, group, null, data);
    }

    public static <T> LogEvent<T> create(@NotNull String message, @NotNull String group) {
        return create(message, group, null, null);
    }

    public static <T> LogEvent<T> create(@NotNull String message, @NotNull String group, @Nullable String topic) {
        return create(message, group, topic, null);
    }

    public @NotNull String message() {
        return message;
    }

    public @NotNull String group() {
        return group;
    }

    public @Nullable String topic() {
        return topic;
    }

    public @Nullable Map<String, T> data() {
        return data;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (LogEvent<?>) obj;
        return Objects.equals(this.message, that.message) &&
                Objects.equals(this.group, that.group) &&
                Objects.equals(this.topic, that.topic) &&
                Objects.equals(this.data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, group, topic, data);
    }

    @Override
    public String toString() {
        final var json = new JsonObject();
        json.put("message", message);
        json.put("group", group);
        if (topic != null) json.put("topic", topic);
        if (data != null) json.put("data", data);
        return json.toString();
    }

}
