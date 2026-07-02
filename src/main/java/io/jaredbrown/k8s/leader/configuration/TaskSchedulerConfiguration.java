package io.jaredbrown.k8s.leader.configuration;

import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration
public class TaskSchedulerConfiguration {
    @Nonnull
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // DistributedLock.unlock() is thread-owned (see ElectorService#awaitLockRelease): a pool
        // size above 1 lets lock acquisition (lockLoop) and renewal/release (refreshLock) run on
        // different worker threads, which throws IllegalStateException off the acquiring thread. A
        // single thread keeps every lock operation sequential and on the same thread by construction.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("elector-");
        scheduler.setDaemon(true);
        // ExecutorConfigurationSupport listens for ContextClosedEvent and, by default, calls
        // executor.shutdown() right there - which is published (and handled synchronously) BEFORE
        // Spring invokes any SmartLifecycle#stop() (see AbstractApplicationContext#doClose: the
        // ContextClosedEvent publish precedes lifecycleProcessor.onClose()). Since ElectorService's
        // own stop() submits the shutdown-time lock release to this same scheduler, without this
        // flag that submit() would always throw TaskRejectedException and the lock would leak every
        // graceful shutdown - the exact bug this scheduler exists to avoid. Setting this defers the
        // executor's shutdown to its later @PreDestroy/DisposableBean callback, which runs after all
        // SmartLifecycle beans have stopped.
        scheduler.setAcceptTasksAfterContextClose(true);
        scheduler.initialize();
        return scheduler;
    }

    // System clock for time-based logic (e.g. the deadlock-grace window). Injected so tests can
    // supply a controllable clock.
    @Nonnull
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
