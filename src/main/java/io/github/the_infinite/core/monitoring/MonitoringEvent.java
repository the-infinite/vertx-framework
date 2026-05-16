package io.github.the_infinite.core.monitoring;

import io.github.the_infinite.core.logging.correlation.CorrelationContext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// Denotes a single event that would publish a given action event to the console. We use records because log events are
/// immutable. There is no situation that generally expects them to be changed or edited at any given point in time.
///
/// @param id          A unique identifier that is used to denote the ID of this log event itself.
/// @param timestamp   When this log was published to the console.
/// @param correlation A unique context that is used analytics to build a representation of what happened and when.
/// @param group       A human-readable string that indicates where this monitoring event comes from.
/// @param type        What application type this event should get published as.
/// @param message     The summarized and human-readable message having to do with this log event. Stripped of data.
/// @param data        The additional data we want to associate to this `LogEvent`.
/// @param topic       An optional topic that this event is associated with, if any.
@SuppressWarnings("unused")
public record MonitoringEvent(
  long id,
  long timestamp,
  Long duration,
  @NotNull CorrelationContext correlation,
  @NotNull String group,
  @Nullable String topic,
  @NotNull MonitoringEvent.Level type,
  @NotNull String message,
  @Nullable Map<String, Object> data
) {
  public MonitoringEvent(
    long id,
    long timestamp,
    @NotNull CorrelationContext correlation,
    @NotNull String group,
    @NotNull MonitoringEvent.Level type,
    @NotNull String message,
    @Nullable Map<String, Object> data
  ) {
    this(id, timestamp, null, correlation, group, null, type, message, data);
  }

    public enum Level {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    TIME,
    EXEC,
  }
}
