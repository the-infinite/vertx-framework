package io.github.the_infinite.core.deploy;

import io.github.the_infinite.core.ConfigurationRegistrant;
import io.github.the_infinite.core.job.JobRegistry;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

@SuppressWarnings("unused")
public final class WorkerVerticle extends VerticleBase {
  private JobRegistry registry = null;

  @Override
  public Future<?> start() {
    return ConfigurationRegistrant.getInstance(vertx).worker().onSuccess(registry -> this.registry = registry);
  }

  @Override
  public Future<?> stop() {
    if (registry != null) {
      return registry.stopJobs();
    }
    return Future.succeededFuture();
  }
}
