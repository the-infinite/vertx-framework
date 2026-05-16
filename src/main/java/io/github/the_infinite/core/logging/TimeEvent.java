package io.github.the_infinite.core.logging;

import java.time.Duration;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("unused")
public final class TimeEvent {
  private final long startTime;
  private final String message;
  private final OnTraceEnd onTraceEnd;
  private Duration runtime;

  public TimeEvent(String message, @NotNull OnTraceEnd onTraceEnd) {
    this.startTime = System.currentTimeMillis();
    this.message = message;
    this.onTraceEnd = onTraceEnd;
    this.runtime = null;
    Objects.requireNonNull(onTraceEnd, "onTraceEnd handler cannot be null");
  }

  public void end() {
    stop();
    this.onTraceEnd.handle(this.message, this.runtime);
  }

  public void stop() {
    if (this.runtime != null) {
      return;
    }
    long endTime = System.currentTimeMillis();
    this.runtime = Duration.ofMillis(endTime - startTime);
  }

  public Duration getRuntime() {
    return this.runtime;
  }

  public interface OnTraceEnd {
    void handle(String message, Duration runtime);
  }
}
