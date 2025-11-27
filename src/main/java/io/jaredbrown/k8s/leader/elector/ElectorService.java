package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ElectorService implements SmartLifecycle {
    private final LockCallbacks callbacks;
    private final ElectorProperties electorProperties;
    private final RedisLockRegistry lockRegistry;
    private volatile DistributedLock lock;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lock-manager");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private volatile ScheduledFuture<?> refreshFuture;

    @Override
    public void start() {
        running = true;
        log.info("Starting DistributedLockManager");
        scheduler.submit(this::lockLoop);
    }

    @Override
    public void stop() {
        log.info("Stopping DistributedLockManager");
        running = false;
        cancelRefreshTask();
        releaseLockIfHeld();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate in time, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for scheduler termination, forcing shutdown");
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @PreDestroy
    public void onDestroy() {
        log.info("@PreDestroy: releasing lock if held");
        stop();
    }


    private void lockLoop() {
        while (running) {
            try {
                log.info("Attempting to acquire lock '{}' ...", electorProperties.getLockName());
                lock = lockRegistry.obtain(electorProperties.getLockName());
                final boolean acquired = lock.tryLock(electorProperties.getRetryPeriod(),
                                                      electorProperties.getLeaseDuration());

                if (acquired) {
                    log.info("Lock '{}' acquired", electorProperties.getLockName());
                    callbacks.onLockAcquired();
                    scheduleRefreshTask();
                    return;
                } else {
                    log.info("Could not acquire lock, will retry in {} seconds", electorProperties.getRetryPeriod());
                }

            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                log.warn("Lock acquisition interrupted, exiting lock loop");
                return;
            } catch (final Exception e) {
                log.error("Error while trying to acquire lock", e);
            }
        }
    }

    // --- Refresh logic: interval is configurable via electorProperties.getRenewDeadline() ---

    private void scheduleRefreshTask() {
        cancelRefreshTask(); // in case there is an old one

        refreshFuture = scheduler.scheduleAtFixedRate(this::refreshLock,
                                                      electorProperties.getRenewDeadline().getSeconds(),
                                                      electorProperties.getRenewDeadline().getSeconds(),
                                                      TimeUnit.SECONDS);
    }

    private void refreshLock() {
        if (!running) {
            handleLockLost();
            return;
        }
        try {

            lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
            log.debug("Lock TTL extended by {} seconds", electorProperties.getLeaseDuration().get(ChronoUnit.SECONDS));

        } catch (final Exception e) {
            log.error("Error while refreshing lock, treating as lock lost", e);
            handleLockLost();
        }
    }

    // --- Lock lost handling & reacquire ---

    private void handleLockLost() {
        cancelRefreshTask();
        releaseLockIfHeld(); // be defensive

        callbacks.onLockLost();

        if (running) {
            // Try to become leader again
            log.info("Scheduling re-acquire of lock after loss");
            scheduler.submit(this::lockLoop);
        }
    }

    private void cancelRefreshTask() {
        if (refreshFuture != null && !refreshFuture.isCancelled()) {
            refreshFuture.cancel(true);
        }
        refreshFuture = null;
    }

    private void releaseLockIfHeld() {
        try {
            if (lock != null) {
                log.info("Releasing lock '{}'", electorProperties.getLockName());
                lock.unlock();
            }
        }  catch (final Exception e) {
            log.error("Error while releasing lock", e);
        }
    }

    // Optional: control startup/shutdown ordering if needed
    @Override
    public int getPhase() {
        // Lower phase -> start earlier, stop later.
        // Adjust if you have other components that depend on the lock.
        return Integer.MIN_VALUE;
    }
}
