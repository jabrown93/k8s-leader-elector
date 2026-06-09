package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElectorService implements SmartLifecycle {
    @Nonnull
    private final LockCallbacks callbacks;
    @Nonnull
    private final ElectorProperties electorProperties;
    @Nonnull
    private final RedisLockRegistry lockRegistry;
    @Nonnull
    private final TaskScheduler taskScheduler;
    @Nonnull
    private final HealthProbe healthProbe;
    @Nonnull
    private final Clock clock;

    private final AtomicReference<DistributedLock> lock = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> refreshFuture = new AtomicReference<>();

    // When the lock is free but this pod keeps failing its health probe, this records when that
    // standoff began so the deadlock-grace escape hatch can fire. Reset whenever a leader exists
    // (we couldn't get the lock) or we successfully lead.
    private final AtomicReference<Instant> deadlockSince = new AtomicReference<>();
    // Consecutive health-probe failures observed while already leading.
    private final AtomicInteger consecutiveProbeFailures = new AtomicInteger(0);

    @Override
    public void start() {
        running.set(true);
        deadlockSince.set(null);
        consecutiveProbeFailures.set(0);
        log.info("Starting ElectorService");
        taskScheduler.schedule(this::lockLoop, clock.instant());
    }

    @Override
    public void stop() {
        log.info("Stopping ElectorService");
        running.set(false);
        cancelRefreshTask();
        releaseLockIfHeld();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    public void onDestroy() {
        log.info("@PreDestroy: releasing lock if held");
        stop();
    }

    private void cancelRefreshTask() {
        final ScheduledFuture<?> future = refreshFuture.getAndSet(null);
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }
    }

    // --- Refresh logic: interval is configurable via electorProperties.getRenewDeadline() ---

    private void releaseLockIfHeld() {
        final DistributedLock currentLock = lock.getAndSet(null);
        if (currentLock != null) {
            try {
                log.info("Releasing lock '{}'", electorProperties.getLockName());
                currentLock.unlock();
            } catch (final Exception e) {
                log.error("Error while releasing lock", e);
            }
        }
    }

    private void lockLoop() {
        if (!running.get()) {
            return;
        }

        try {
            // Probe first so the result is known whether or not the lock is free. We still attempt
            // the lock even when unhealthy: succeeding tells us the lock is unheld, which is what
            // the deadlock escape hatch needs to distinguish "no healthy candidate" from "a
            // healthy leader already exists".
            final boolean healthy = healthProbe.isHealthy();
            log.info("Attempting to acquire lock '{}'... (healthy={})", electorProperties.getLockName(), healthy);
            final DistributedLock newLock = lockRegistry.obtain(electorProperties.getLockName());
            final boolean acquired = newLock.tryLock(electorProperties
                                                       .getRetryPeriod()
                                                       .getSeconds(), TimeUnit.SECONDS);

            if (acquired) {
                if (healthy) {
                    becomeLeader(newLock);
                } else if (deadlockGraceExceeded()) {
                    log.warn("Breaking leadership deadlock: acquiring lock '{}' despite a failing health probe " +
                             "(no healthy candidate for at least {}). Leading in a DEGRADED state.",
                             electorProperties.getLockName(),
                             electorProperties.getHealthProbeDeadlockGrace());
                    becomeLeader(newLock);
                } else {
                    // Lock is free but we're unhealthy and still within the grace window. Don't
                    // lead yet — release so a healthy peer can take over.
                    boolean released = true;
                    try {
                        newLock.unlock();
                    } catch (final Exception e) {
                        released = false;
                        log.error("Error releasing transiently held lock", e);
                    }
                    if (released) {
                        // Released cleanly: back off well past retryPeriod so we stop re-grabbing the
                        // free lock every few seconds and starving the healthy peers racing for it (the
                        // livelock that leaves the deployment leaderless); the backoff yields them
                        // uncontested windows.
                        log.info("Lock '{}' is free but this pod fails its health probe; released and " +
                                 "backing off {} so a healthy peer can lead",
                                 electorProperties.getLockName(), electorProperties.getHealthProbeUnhealthyBackoff());
                        scheduleUnhealthyRetry();
                    } else {
                        // Release failed, so we may still hold the lock — backing off the long interval
                        // would keep healthy peers blocked. Re-probe on the short retryPeriod to retry
                        // the release promptly instead.
                        log.warn("Lock '{}' may still be held after a failed release; retrying in {} to release it",
                                 electorProperties.getLockName(), electorProperties.getRetryPeriod());
                        scheduleRetry();
                    }
                }
            } else {
                // Someone else holds the lock: a leader exists, so we are not deadlocked.
                deadlockSince.set(null);
                // An unhealthy pod still backs off the longer interval: it has no business racing for
                // leadership, and a tight retry only adds churn while a leader already exists.
                if (healthy) {
                    log.info("Could not acquire lock, will retry in {}", electorProperties.getRetryPeriod());
                    scheduleRetry();
                } else {
                    log.info("Could not acquire lock (a leader exists); unhealthy, will retry in {}",
                             electorProperties.getHealthProbeUnhealthyBackoff());
                    scheduleUnhealthyRetry();
                }
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            log.warn("Lock acquisition interrupted, exiting lock loop", e);
        } catch (final Exception e) {
            log.error("Error while trying to acquire lock, retrying in {}", electorProperties.getRetryPeriod(), e);
            if (running.get()) {
                scheduleRetry();
            }
        }
    }

    private void becomeLeader(final DistributedLock newLock) {
        // Acquiring leadership ends any current free-lock standoff, so the deadlock-grace window
        // must start fresh next time. Resetting here (rather than only on the healthy path) stops a
        // degraded leader that later relinquishes from immediately re-acquiring on the stale timer.
        deadlockSince.set(null);
        consecutiveProbeFailures.set(0);
        lock.set(newLock);
        log.info("Lock '{}' acquired", electorProperties.getLockName());
        try {
            callbacks.onLockAcquired();
        } catch (final Exception e) {
            log.error("Lock acquired, but post-acquire callback failed; releasing lock and retrying in {}",
                      electorProperties.getRetryPeriod(),
                      e);
            releaseLockIfHeld();
            scheduleRetry();
            return;
        }
        scheduleRefreshTask();
    }

    // True once the lock has been observed free-but-this-pod-unhealthy for at least the configured
    // grace. Starts the timer on first such observation (returning false then).
    private boolean deadlockGraceExceeded() {
        final Instant now = clock.instant();
        final Instant witness = deadlockSince.compareAndExchange(null, now);
        final Instant since = (witness == null) ? now : witness;
        return Duration
                       .between(since, now)
                       .compareTo(electorProperties.getHealthProbeDeadlockGrace()) >= 0;
    }

    private void scheduleRetry() {
        if (running.get()) {
            taskScheduler.schedule(this::lockLoop,
                                   clock
                                           .instant()
                                           .plus(electorProperties.getRetryPeriod()));
        }
    }

    // Re-probe schedule for an UNHEALTHY pod: a longer backoff than retryPeriod so it stops
    // contending for the lock every few seconds and lets healthy peers take over (see lockLoop).
    private void scheduleUnhealthyRetry() {
        if (running.get()) {
            taskScheduler.schedule(this::lockLoop,
                                   clock
                                           .instant()
                                           .plus(electorProperties.getHealthProbeUnhealthyBackoff()));
        }
    }

    // --- Lock lost handling & reacquire ---

    private void scheduleRefreshTask() {
        cancelRefreshTask();
        final ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(this::refreshLock,
                                                                            clock
                                                                              .instant()
                                                                              .plus(electorProperties.getRenewDeadline()),
                                                                            electorProperties.getRenewDeadline());
        refreshFuture.set(future);
    }

    private void refreshLock() {
        if (!running.get()) {
            handleLockLost();
            return;
        }
        try {
            // Relinquish leadership if we go unhealthy while leading, but only after a run of
            // failures so a transient blip (or a normal gravity rebuild) doesn't cause flapping.
            // isHealthy() returns true when probing is disabled, so this is a no-op then.
            if (!healthProbe.isHealthy()) {
                final int failures = consecutiveProbeFailures.incrementAndGet();
                final int threshold = electorProperties.getHealthProbeFailureThreshold();
                if (failures >= threshold) {
                    log.warn("Health probe failed {} consecutive times (threshold {}) while leading; " +
                             "relinquishing leadership of '{}'",
                             failures,
                             threshold,
                             electorProperties.getLockName());
                    handleLockLost();
                    return;
                }
                log.warn("Health probe failing while leading ({}/{}); will relinquish '{}' if it continues",
                         failures,
                         threshold,
                         electorProperties.getLockName());
            } else {
                consecutiveProbeFailures.set(0);
            }

            lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
            log.debug("Lock TTL extended by {} seconds",
                      electorProperties
                              .getLeaseDuration()
                              .get(ChronoUnit.SECONDS));
        } catch (final Exception e) {
            log.error("Error while refreshing lock, treating as lock lost", e);
            handleLockLost();
        }
    }

    private void handleLockLost() {
        cancelRefreshTask();
        releaseLockIfHeld();
        consecutiveProbeFailures.set(0);

        callbacks.onLockLost();

        if (running.get()) {
            log.info("Scheduling re-acquire of lock after loss");
            taskScheduler.schedule(this::lockLoop, clock.instant());
        }
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
