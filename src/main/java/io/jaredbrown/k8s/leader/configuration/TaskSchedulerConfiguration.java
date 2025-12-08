package io.jaredbrown.k8s.leader.configuration;

import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class TaskSchedulerConfiguration {
    @Nonnull
    @Bean
    public TaskScheduler taskScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("elector-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
