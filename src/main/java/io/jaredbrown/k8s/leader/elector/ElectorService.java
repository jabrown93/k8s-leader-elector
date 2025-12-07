package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElectorService implements SmartLifecycle {
    private final LockCallbacks callbacks;
    private final ElectorProperties electorProperties;
    private final RedisLockRegistry lockRegistry;
    private final TaskScheduler taskScheduler;

    private volatile DistributedLock lock;
    private volatile boolean running = false;
    private volatile ScheduledFuture<?> refreshFuture;

    @Override
    public void start() {
        running = true;
        log.info("Starting ElectorService");
        taskScheduler.schedule(this::lockLoop, Instant.now());
    }

    @Override
    public void stop() {
        log.info("Stopping ElectorService");
        running = false;
        cancelRefreshTask();
        releaseLockIfHeld();
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
        if (!running) {
            return;
        }

        try {
            log.info("Attempting to acquire lock '{}'...", electorProperties.getLockName());
            lock = lockRegistry.obtain(electorProperties.getLockName());
            boolean acquired = lock.tryLock(electorProperties.getRetryPeriod().getSeconds(), TimeUnit.SECONDS);

            if (acquired) {
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
            if (running) {
                taskScheduler.schedule(this::lockLoop, Instant.now().plus(electorProperties.getRetryPeriod()));
            }
        }
    }

    // --- Refresh logic: interval is configurable via electorProperties.getRenewDeadline() ---

    private void scheduleRefreshTask() {
        cancelRefreshTask();
        refreshFuture = taskScheduler.scheduleAtFixedRate(
            this::refreshLock,
            Instant.now().plus(electorProperties.getRenewDeadline()),
            electorProperties.getRenewDeadline()
        );
    }

    private void refreshLock() {
        if (!running) {
            handleLockLost();
            return;
        }
        try {
            lockRegistry.renewLock(electorProperties.getLockName(), electorProperties.getLeaseDuration());
            log.debug("Lock TTL extended by {} seconds", electorProperties.getLeaseDuration().get(ChronoUnit.SECONDS));
        } catch (Exception e) {
            log.error("Error while refreshing lock, treating as lock lost", e);
            handleLockLost();
        }
    }

    // --- Lock lost handling & reacquire ---

    private void handleLockLost() {
        cancelRefreshTask();
        releaseLockIfHeld();

        callbacks.onLockLost();

        if (running) {
            log.info("Scheduling re-acquire of lock after loss");
            taskScheduler.schedule(this::lockLoop, Instant.now());
        }
    }

    private void cancelRefreshTask() {
        if (refreshFuture != null && !refreshFuture.isCancelled()) {
            refreshFuture.cancel(true);
        }
        refreshFuture = null;
    }

    private void releaseLockIfHeld() {
        DistributedLock currentLock = lock;
        if (currentLock != null) {
            try {
                log.info("Releasing lock '{}'", electorProperties.getLockName());
                currentLock.unlock();
            } catch (Exception e) {
                log.error("Error while releasing lock", e);
            } finally {
                lock = null;
            }
        }
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
