package io.github.the_infinite.core.service;

import io.github.the_infinite.core.logging.correlation.CorrelationContext;
import io.github.the_infinite.core.logging.monitor.LogEvent;
import io.github.the_infinite.core.logging.monitor.MonitorLogger;
import io.github.the_infinite.core.response.ErrorResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.CircuitBreakerState;
import io.vertx.core.Vertx;

@SuppressWarnings("unused")
public abstract class ApplicationService<ProviderType extends ServiceProvider> {
  protected final MonitorLogger logger;
  private final String name;
  private final CircuitBreakerOptions options;
  private final Vertx vertx;
  private ChainOfResponsibilityNodeImpl<ProviderType> preferredProvider;
  private ChainOfResponsibilityNodeImpl<ProviderType> previousProvider;

  public ApplicationService(final String name, final Vertx vertx, final MonitorLogger logger, final CircuitBreakerOptions options) {
    this.name = "Services.%s".formatted(name);
    this.options = options;
    this.logger = logger;
    this.vertx = vertx;
  }

  /// This gives you a string that can be used by the monitor logger
  public String groupId() {
    return name;
  }

  ///  This registers a new provider as our preferred provider
  public void registerProvider(ProviderType provider) {
    if (this.preferredProvider == null) {
      this.preferredProvider = new ChainOfResponsibilityNodeImpl<>(provider, vertx, options);
      return;
    }

    var current = preferredProvider;
    while (current.substitute.isPresent()) {
      current = current.substitute.get();
    }

    current.substitute = Optional.of(new ChainOfResponsibilityNodeImpl<>(provider, vertx, options));
  }

  public Optional<ProviderType> getProviderByName(String name) {
    var current = preferredProvider;
    do {
      if (current.provider.getName().equals(name)) {
        return Optional.of(current.provider);
      }

      //? For the sake of the first iteration
      if (current.substitute.isPresent()) current = current.substitute.get();
    } while ((current.substitute.isPresent()));

    //? Empty optional.
    return Optional.empty();
  }

  public List<ProviderType> listProviders() {
    final var providers = new ArrayList<ProviderType>();

    var current = this.preferredProvider;
    do {
      providers.add(current.provider);

      //? For the sake of the first iteration
      if (current.substitute.isPresent()) current = current.substitute.get();
    } while ((current.substitute.isPresent()));

    return providers;
  }

  public boolean hasProviders() {
    return preferredProvider != null;
  }

  public List<String> providerNames() {
    return listProviders().stream().map(ServiceProvider::getName).toList();
  }

  public Optional<ChainOfResponsibilityNode<ProviderType>> getAvailableProvision(CorrelationContext context) {
    var current = preferredProvider;

    do {
      if (current.breaker.state() == CircuitBreakerState.CLOSED) {
        //? If this is not the previous provider...
        if (this.previousProvider != null && !previousProvider.equals(current)) {
          this.logger.info(context, LogEvent.create(
            "Switching current ServiceProvider for a service",
            this.groupId(),
            Map.of(
              "from", this.previousProvider.getProviderName(),
              "to", current.getProviderName()
            )
          ));
        }

        //? If the new provider is not the preferred provider
        if (!this.preferredProvider.equals(current)) {
          this.logger.warn(context, LogEvent.create(
            "Using a fallback service provider since the primary provider is not available. Contact support if this persists.",
            this.groupId(),
            Map.of(
              "preferred", this.preferredProvider.getProviderName(),
              "using", current.getProviderName()
            )
          ));
        }

        //? Since we can then switch away from the
        this.previousProvider = current;
        return Optional.of(current);
      }

      //? If this provider is now healthy...
      if (current.provider.isHealthy()) {
        final var breaker = current.breaker;

        //? Pass the metrics of the circuit breaker to the monitoring logger for this service.
        this.logger.warn(context, LogEvent.create(
          "A service provider is now healthy again. Dumping previous metrics.",
          this.groupId(),
          Map.of(
            "preferred", this.preferredProvider.getProviderName(),
            "provider", current.getProviderName(),
            "state", breaker.state().name(),
            "failCount", breaker.failureCount(),
            "okayCount", current.successCount,
            "lastFailedAt", current.lastFailedAt,
            "lastOkAt", current.lastSucceededAt,
            "lastError", current.lastError == null ? "<N/A>" : ErrorResult.of(current.lastError)
          )
        ));

        //? Reset the metrics and then try again.
        current.reset();
        return Optional.of(current);
      }
    } while (current.substitute.isPresent());

    //? Nothing to do therefore return empty.
    return Optional.empty();
  }
}
