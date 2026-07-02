package io.jaredbrown.k8s.leader.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulerConfigurationTest {

    @Test
    void taskScheduler_shouldBeSingleThreadedAndAcceptTasksAfterContextClose() {
        final ThreadPoolTaskScheduler scheduler = new TaskSchedulerConfiguration().taskScheduler();

        // getPoolSize() reflects the live executor's current thread count (0 until a task actually
        // runs) once initialized, not the configured value - so read the configured field directly.
        assertEquals(1, (int) ReflectionTestUtils.getField(scheduler, "poolSize"));
        // Regression guard: without this flag, ExecutorConfigurationSupport shuts the executor down
        // on ContextClosedEvent - which fires before any SmartLifecycle#stop() runs - so
        // ElectorService#awaitLockRelease's submit() would always throw TaskRejectedException and
        // the Redis lock would leak on every graceful shutdown.
        assertTrue((boolean) ReflectionTestUtils.getField(scheduler, "acceptTasksAfterContextClose"));
    }
}
