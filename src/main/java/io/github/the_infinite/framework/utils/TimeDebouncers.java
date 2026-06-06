package io.github.the_infinite.framework.utils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Utility factory for time-based debouncer variants.
 */
public final class TimeDebouncers {
    private TimeDebouncers() {
    }

    public interface Debouncer {
        void trigger();

        boolean cancelPending();
    }

    public interface ValueDebouncer<T> {
        void trigger(T value);

        boolean cancelPending();
    }

    /**
     * Trailing-edge debouncer. Executes after there have been no triggers for {@code delay}.
     */
    public static Debouncer trailing(Duration delay,
                                     ScheduledExecutorService scheduler,
                                     Runnable action) {
        return new TrailingDebouncer(delay, scheduler, action);
    }

    /**
     * Leading-edge debouncer. Executes immediately, then suppresses triggers until {@code window} elapses.
     */
    public static Debouncer leading(Duration window, Runnable action) {
        return new LeadingDebouncer(window, action);
    }

    /**
     * Trailing-edge debouncer for values. Only the latest value in the active debouncing window is emitted.
     */
    public static <T> ValueDebouncer<T> trailingValue(Duration delay,
                                                      ScheduledExecutorService scheduler,
                                                      Consumer<T> action) {
        return new TrailingValueDebouncer<>(delay, scheduler, action);
    }

    private static final class TrailingDebouncer implements Debouncer {
        private final long delayNanos;
        private final ScheduledExecutorService scheduler;
        private final Runnable action;
        private final Object lock = new Object();
        private ScheduledFuture<?> future;

        private TrailingDebouncer(Duration delay,
                                  ScheduledExecutorService scheduler,
                                  Runnable action) {
            this.delayNanos = validateDuration(delay, "delay").toNanos();
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.action = Objects.requireNonNull(action, "action");
        }

        @Override
        public void trigger() {
            synchronized (lock) {
                if (future != null) {
                    future.cancel(false);
                }
                future = scheduler.schedule(() -> {
                    synchronized (lock) {
                        future = null;
                    }
                    action.run();
                }, delayNanos, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public boolean cancelPending() {
            synchronized (lock) {
                if (future == null) {
                    return false;
                }
                boolean canceled = future.cancel(false);
                future = null;
                return canceled;
            }
        }
    }

    private static final class LeadingDebouncer implements Debouncer {
        private final long windowNanos;
        private final Runnable action;
        private final AtomicLong nextAllowedNanos = new AtomicLong(Long.MIN_VALUE);

        private LeadingDebouncer(Duration window, Runnable action) {
            this.windowNanos = validateDuration(window, "window").toNanos();
            this.action = Objects.requireNonNull(action, "action");
        }

        @Override
        public void trigger() {
            long now = System.nanoTime();
            while (true) {
                long nextAllowed = nextAllowedNanos.get();
                if (now < nextAllowed) {
                    return;
                }

                long updated = now + windowNanos;
                if (nextAllowedNanos.compareAndSet(nextAllowed, updated)) {
                    action.run();
                    return;
                }
            }
        }

        @Override
        public boolean cancelPending() {
            // Leading variant has no pending scheduled task.
            return false;
        }
    }

    private static final class TrailingValueDebouncer<T> implements ValueDebouncer<T> {
        private final AtomicReference<T> latestValue = new AtomicReference<>();
        private final Debouncer delegate;

        private TrailingValueDebouncer(Duration delay,
                                       ScheduledExecutorService scheduler,
                                       Consumer<T> action) {
            Objects.requireNonNull(action, "action");
            this.delegate = trailing(delay, scheduler, () -> action.accept(latestValue.get()));
        }

        @Override
        public void trigger(T value) {
            latestValue.set(value);
            delegate.trigger();
        }

        @Override
        public boolean cancelPending() {
            return delegate.cancelPending();
        }
    }

    private static Duration validateDuration(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return duration;
    }
}
