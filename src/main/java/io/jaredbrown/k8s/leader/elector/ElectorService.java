package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElectorService implements SmartLifecycle {
    private final LockCallbacks callbacks;
    private final ElectorProperties electorProperties;
    private final RedisLockRegistry lockRegistry;
    private final TaskScheduler taskScheduler;

    private final AtomicReference<DistributedLock> lock = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> refreshFuture = new AtomicReference<>();

    @Override
    public void start() {
        running.set(true);
        log.info("Starting ElectorService");
        taskScheduler.schedule(this::lockLoop, Instant.now());
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
        ScheduledFuture<?> future = refreshFuture.getAndSet(null);
        if (future != null && !future.isCancelled()) {
            future.cancel(true);
        }
    }

    // --- Refresh logic: interval is configurable via electorProperties.getRenewDeadline() ---

    private void releaseLockIfHeld() {
        DistributedLock currentLock = lock.getAndSet(null);
        if (currentLock != null) {
            try {
                log.info("Releasing lock '{}'", electorProperties.getLockName());
                currentLock.unlock();
            } catch (Exception e) {
                log.error("Error while releasing lock", e);
            }
        }
    }

    private void lockLoop() {
        if (!running.get()) {
            return;
        }

        try {
            log.info("Attempting to acquire lock '{}'...", electorProperties.getLockName());
            DistributedLock newLock = lockRegistry.obtain(electorProperties.getLockName());
            boolean acquired = newLock.tryLock(
                electorProperties.getRetryPeriod().getSeconds(),
                TimeUnit.SECONDS
            );

            if (acquired) {
                lock.set(newLock);
                log.info("Lock '{}' acquired", electorProperties.getLockName());
                callbacks.onLockAcquired();
                scheduleRefreshTask();
            } else {
                log.info("Could not acquire lock, will retry in {}", electorProperties.getRetryPeriod());
                taskScheduler.schedule(this::lockLoop, Instant.now().plus(electorProperties.getRetryPeriod()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Lock acquisition interrupted, exiting lock loop");
        } catch (Exception e) {
            log.error("Error while trying to acquire lock, retrying in {}", electorProperties.getRetryPeriod(), e);
            if (running.get()) {
                taskScheduler.schedule(this::lockLoop, Instant.now().plus(electorProperties.getRetryPeriod()));
            }
        }
    }

    // --- Lock lost handling & reacquire ---

    private void scheduleRefreshTask() {
        cancelRefreshTask();
        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
            this::refreshLock,
            Instant.now().plus(electorProperties.getRenewDeadline()),
            electorProperties.getRenewDeadline()
        );
        refreshFuture.set(future);
    }

    private void refreshLock() {
        if (!running.get()) {
            handleLockLost();
            return;
        }
        try {
            lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
            log.debug("Lock TTL extended by {} seconds",
                electorProperties.getLeaseDuration().get(ChronoUnit.SECONDS));
        } catch (Exception e) {
            log.error("Error while refreshing lock, treating as lock lost", e);
            handleLockLost();
        }
    }

    private void handleLockLost() {
        cancelRefreshTask();
        releaseLockIfHeld();

        callbacks.onLockLost();

        if (running.get()) {
            log.info("Scheduling re-acquire of lock after loss");
            taskScheduler.schedule(this::lockLoop, Instant.now());
        }
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
