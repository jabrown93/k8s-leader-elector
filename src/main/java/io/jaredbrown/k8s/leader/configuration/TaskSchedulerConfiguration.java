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
     * @return a daemon {@link ThreadPoolTaskScheduler} pinned to a single thread, because
     * {@code DistributedLock.unlock()} is thread-owned: a larger pool would let acquisition
     * ({@code lockLoop}) and renewal/release ({@code refreshLock}) land on different threads and
     * throw {@code IllegalStateException}. {@code acceptTasksAfterContextClose} keeps the executor
     * alive long enough for {@code ElectorService#stop()} to submit its lock release — see "Why the
     * Scheduler Accepts Tasks After Context Close" in {@code docs/codebase/ARCHITECTURE.md}.
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
