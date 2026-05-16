package io.github.the_infinite.framework.app;

import io.vertx.core.VertxOptions;
import lombok.Builder;

@Builder
public record StartupOptions(int workerMaxExecuteTimeMinutes,
                             int eventLoopMaxExecuteTimeMinutes,
                             int blockedThreadCheckIntervalMillis, int workerPoolSize,
                             int eventLoopPoolSize, int serviceWeight,
                             String serviceProtocol, String authType) {
  public static StartupOptions defaultOptions() {
    return new StartupOptions(
      2,
      1,
      750,
      VertxOptions.DEFAULT_WORKER_POOL_SIZE,
      VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE,
      1,
      "http",
      "token"
    );
  }
}
