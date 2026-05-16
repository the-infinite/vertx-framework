package io.github.the_infinite.core.monitoring;

import io.github.the_infinite.core.response.ErrorResult;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.stream.Stream;

import io.vertx.core.Future;

@SuppressWarnings("unused")
public interface ILogMonitor {
  Future<Long> getProvisionalId();

  Future<Collection<Long>> getIdsFor(int count);

  Future<Void> publishEvent(MonitoringEvent event);

  Future<BulkResult> publishEvents(Collection<MonitoringEvent> events);

  Future<BulkResult> streamEvents(Stream<MonitoringEvent> events);

  Future<BulkResult> flushEvents();

  record BulkResult(int succeeded, int failed, @Nullable Iterable<ErrorResult> errors) {
  }
}
