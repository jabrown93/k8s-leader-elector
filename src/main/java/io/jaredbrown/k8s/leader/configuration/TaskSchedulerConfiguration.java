package io.jaredbrown.k8s.leader.configuration;

import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

/**
 * Beans supporting {@code ElectorService}'s scheduling: a single-threaded task scheduler and an
 * injectable clock.
 */
@Configuration
public class TaskSchedulerConfiguration {
    /**
     * @return a daemon {@link ThreadPoolTaskScheduler} pinned to a single thread.
     * <p>{@code DistributedLock.unlock()} is thread-owned (see {@code
     * ElectorService#awaitLockRelease}): a pool size above 1 would let lock acquisition
     * ({@code lockLoop}) and renewal/release ({@code refreshLock}) run on different worker
     * threads, throwing {@code IllegalStateException} off the acquiring thread. A single thread
     * keeps every lock operation sequential and on the same thread by construction.
     * <p>{@code setAcceptTasksAfterContextClose(true)} is required, not cosmetic:
     * {@code ExecutorConfigurationSupport} listens for {@code ContextClosedEvent} and, by default,
     * calls {@code executor.shutdown()} right there — published (and handled synchronously)
     * BEFORE Spring invokes any {@code SmartLifecycle#stop()} (see
     * {@code AbstractApplicationContext#doClose}: the {@code ContextClosedEvent} publish precedes
     * {@code lifecycleProcessor.onClose()}). Since {@code ElectorService}'s own {@code stop()}
     * submits the shutdown-time lock release to this same scheduler, without this flag that
     * {@code submit()} call would always throw {@code TaskRejectedException} and the lock would
     * leak every graceful shutdown — the exact bug this scheduler's thread-pinning exists to
     * avoid. Setting this defers the executor's shutdown to its later
     * {@code @PreDestroy}/{@code DisposableBean} callback, which runs after all
     * {@code SmartLifecycle} beans have stopped.
     */
    @Nonnull
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("elector-");
        scheduler.setDaemon(true);
        scheduler.setAcceptTasksAfterContextClose(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * @return the system UTC clock, injected (rather than called directly) so tests can supply a
     * controllable clock for time-based logic such as the deadlock-grace window
     */
    @Nonnull
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
