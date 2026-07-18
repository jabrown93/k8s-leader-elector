package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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

/**
 * Drives distributed leader election over a Redis-backed {@link RedisLockRegistry}, keeping pod
 * leader labels in sync via {@link LockCallbacks}.
 *
 * <p>Acquisition, renewal, and release all run on {@code taskScheduler}'s single thread (see that
 * bean's Javadoc for why); optional health gating and a deadlock-grace escape hatch are described
 * on {@link #lockLoop} and {@link #deadlockGraceExceeded}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElectorService implements SmartLifecycle {
    // Upper bound on how long stop() waits for the scheduler thread to release the lock (see
    // awaitLockRelease). Comfortably inside a pod's terminationGracePeriodSeconds.
    private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(5);

    @Nonnull
    private final LockCallbacks callbacks;
    @Nonnull
    private final ElectorProperties electorProperties;
    @Nonnull
    private final RedisLockRegistry lockRegistry;
    // Concrete type (not the TaskScheduler interface) because stop() needs submit()'s Future to
    // wait for the shutdown-time lock release; see awaitLockRelease.
    @Nonnull
    private final ThreadPoolTaskScheduler taskScheduler;
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

    /**
     * Labels self {@code leader=false} (so a freshly (re)created pod carries the label from boot
     * rather than staying unlabeled until it wins or loses its first election), then schedules the
     * first {@link #lockLoop} run.
     */
    @Override
    public void start() {
        running.set(true);
        deadlockSince.set(null);
        consecutiveProbeFailures.set(0);
        log.info("Starting ElectorService");
        callbacks.ensureSelfLabeled();
        taskScheduler.schedule(this::lockLoop, clock.instant());
    }

    /** Cancels lock renewal and releases the lock (if held); see {@link #awaitLockRelease}. */
    @Override
    public void stop() {
        log.info("Stopping ElectorService");
        running.set(false);
        cancelRefreshTask();
        awaitLockRelease();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Delegates to {@link #stop()} so shutdown releases the lock even outside a normal Spring stop. */
    @PreDestroy
    public void onDestroy() {
        log.info("@PreDestroy: releasing lock if held");
        stop();
    }

    /** Cancels the scheduled renewal task, if one is running. */
    private void cancelRefreshTask() {
        final ScheduledFuture<?> future = refreshFuture.getAndSet(null);
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }
    }

    /**
     * Routes the shutdown-time lock release onto the scheduler thread and waits up to
     * {@link #RELEASE_TIMEOUT} for it to finish.
     *
     * <p>{@code DistributedLock.unlock()} is thread-owned ({@code RedisLockRegistry.RedisLock}
     * wraps a local {@code ReentrantLock} and throws {@code IllegalStateException} if unlocked
     * off-thread). Acquisition always happens on the {@code taskScheduler} thread ({@code
     * lockLoop}/{@code refreshLock}), but {@code SmartLifecycle#stop()} runs on whatever thread
     * Spring's context shutdown uses, so releasing directly here would always fail and leak the
     * Redis key for the full lease TTL.
     */
    private void awaitLockRelease() {
        try {
            taskScheduler
                    .submit(this::releaseLockAndClearLabelIfHeld)
                    .get(RELEASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            log.warn("Interrupted while releasing lock during shutdown", e);
        } catch (final Exception e) {
            log.error("Failed to release lock during shutdown within {}", RELEASE_TIMEOUT, e);
        }
    }

    /**
     * Runs on the scheduler thread (see {@link #awaitLockRelease}). Only a pod that was actually
     * leading needs its label cleared here - a non-leader pod's label is already false.
     */
    private void releaseLockAndClearLabelIfHeld() {
        if (releaseLockIfHeld()) {
            callbacks.onShutdown();
        }
    }

    // --- Refresh logic: interval is configurable via electorProperties.getRenewDeadline() ---

    /**
     * @return whether this pod was holding the lock (i.e. was leader), regardless of whether the
     * unlock call itself succeeded
     */
    private boolean releaseLockIfHeld() {
        final DistributedLock currentLock = lock.getAndSet(null);
        if (currentLock != null) {
            try {
                log.info("Releasing lock '{}'", electorProperties.getLockName());
                currentLock.unlock();
            } catch (final Exception e) {
                log.error("Error while releasing lock", e);
            }
            return true;
        }
        return false;
    }

    /**
     * Attempts lock acquisition, gated by the health probe: an unhealthy pod that still acquires
     * the (free) lock releases it again and backs off (see {@link #scheduleUnhealthyRetry}), unless
     * {@link #deadlockGraceExceeded} says every candidate has been unhealthy long enough to lead in
     * a degraded state anyway. Reschedules itself via {@link #scheduleRetry} or {@link
     * #scheduleUnhealthyRetry} on every path that doesn't lead to {@link #becomeLeader}.
     */
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

    /**
     * Takes ownership of {@code newLock}, reconciles leader labels, and schedules renewal. Releases
     * the lock and retries instead if the post-acquire callback fails.
     */
    private void becomeLeader(final DistributedLock newLock) {
        // Acquiring leadership ends any current free-lock standoff, so the deadlock-grace window
        // must start fresh next time. Resetting here (rather than only on the healthy path) stops a
        // degraded leader that later relinquishes from immediately re-acquiring on the stale timer.
        deadlockSince.set(null);
        consecutiveProbeFailures.set(0);
        lock.set(newLock);
        log.info("Lock '{}' acquired", electorProperties.getLockName());
        try {
            callbacks.onLockAcquired(this::stillOwnsLock);
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

    /**
     * @return {@code true} once the lock has been observed free-but-this-pod-unhealthy for at
     * least the configured grace. Starts the timer on first such observation (returning
     * {@code false} then).
     */
    private boolean deadlockGraceExceeded() {
        final Instant now = clock.instant();
        final Instant witness = deadlockSince.compareAndExchange(null, now);
        final Instant since = (witness == null) ? now : witness;
        return Duration
                       .between(since, now)
                       .compareTo(electorProperties.getHealthProbeDeadlockGrace()) >= 0;
    }

    /** Reschedules {@link #lockLoop} after {@code retryPeriod}, if still running. */
    private void scheduleRetry() {
        if (running.get()) {
            taskScheduler.schedule(this::lockLoop,
                                   clock
                                           .instant()
                                           .plus(electorProperties.getRetryPeriod()));
        }
    }

    /**
     * Re-probe schedule for an unhealthy pod: a longer backoff than {@code retryPeriod} so it stops
     * contending for the lock every few seconds and lets healthy peers take over (see
     * {@link #lockLoop}).
     */
    private void scheduleUnhealthyRetry() {
        if (running.get()) {
            taskScheduler.schedule(this::lockLoop,
                                   clock
                                           .instant()
                                           .plus(electorProperties.getHealthProbeUnhealthyBackoff()));
        }
    }

    // --- Lock lost handling & reacquire ---

    /** Cancels any existing renewal task and schedules {@link #refreshLock} at {@code renewDeadline}. */
    private void scheduleRefreshTask() {
        cancelRefreshTask();
        final ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(this::refreshLock,
                                                                            clock
                                                                              .instant()
                                                                              .plus(electorProperties.getRenewDeadline()),
                                                                            electorProperties.getRenewDeadline());
        refreshFuture.set(future);
    }

    /**
     * Runs on every renewal tick: relinquishes leadership if the health probe has failed
     * {@code healthProbeFailureThreshold} consecutive times, otherwise renews the lock (with one
     * immediate retry; see {@link #renewLockWithRetry}) and reconciles leader labels.
     */
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

            renewLockWithRetry();
            // Self-heals any label a prior attempt failed to set (slow API server, a pod created
            // after the last election) instead of leaving it wrong until the next leadership change.
            // Passes stillOwnsLock so a reconcile that outlives the lease stops before stamping stale
            // labels once another pod has taken over.
            callbacks.reconcileLeaderLabels(this::stillOwnsLock);
        } catch (final Exception e) {
            log.error("Error while refreshing lock, treating as lock lost", e);
            handleLockLost();
        }
    }

    /**
     * Renews the lock, retrying once immediately on failure before propagating. A single transient
     * Redis blip should not cost leadership outright: {@code renewDeadline} (60s default) leaves
     * ample slack before the lease (120s default) actually expires, so one immediate retry absorbs
     * a blip that would otherwise trigger a full re-election.
     */
    private void renewLockWithRetry() {
        try {
            renewLockOnce();
        } catch (final Exception first) {
            log.warn("First attempt to renew lock '{}' failed, retrying once immediately",
                     electorProperties.getLockName(),
                     first);
            renewLockOnce();
        }
    }

    /** Extends the lock's Redis TTL by {@code leaseDuration}; throws on failure. */
    private void renewLockOnce() {
        lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
        log.debug("Lock TTL extended by {} seconds",
                  electorProperties
                          .getLeaseDuration()
                          .get(ChronoUnit.SECONDS));
    }

    /**
     * Re-confirms this pod still holds the Redis lock, and refreshes its lease as a side effect.
     * Passed into {@code LockCallbacks#reconcileLeaderLabels} so a long-running label reconcile
     * stops the moment ownership is lost instead of stamping stale labels. {@code renewLock}'s Lua
     * script only extends the key while Redis still maps it to THIS registry's client id, so a
     * thrown/false result is a genuine "no longer the owner" signal (a lapsed lease another pod
     * already took), not just local state — a purely local check can't see a Redis-side takeover.
     * Runs on the scheduler thread, the same thread as the reconcile that calls it.
     *
     * <p>Also gates on {@code running}: {@code renewLock} alone would keep succeeding straight
     * through shutdown, letting a reconcile spanning many pod-list pages stall the single scheduler
     * thread well past {@code stop()}'s 5s {@link #RELEASE_TIMEOUT}. This matters most for the
     * acquisition-time reconcile ({@code becomeLeader -> onLockAcquired}), which runs before
     * {@link #scheduleRefreshTask()} creates {@code refreshFuture} — {@link #cancelRefreshTask()}
     * has nothing to interrupt yet at that point, so this running check is the only thing that can
     * cut a long reconcile short once {@code stop()} has fired, letting the queued lock-release
     * task run promptly.
     *
     * @return whether this pod's ownership of the lock was confirmed
     */
    boolean stillOwnsLock() {
        if (!running.get() || lock.get() == null) {
            return false;
        }
        try {
            lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
            return true;
        } catch (final Exception e) {
            log.warn("Could not confirm Redis ownership of lock '{}' mid-reconcile; treating as lost",
                     electorProperties.getLockName(),
                     e);
            return false;
        }
    }

    /** Cancels renewal, releases the lock, notifies {@link LockCallbacks#onLockLost()}, and re-enters acquisition if still running. */
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

    /** @return {@link Integer#MIN_VALUE} so this service starts as early as possible. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
