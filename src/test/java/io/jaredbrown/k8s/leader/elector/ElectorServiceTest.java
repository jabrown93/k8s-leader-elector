package io.jaredbrown.k8s.leader.elector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElectorServiceTest {

    @Mock
    private LockCallbacks callbacks;

    @Mock
    private ElectorProperties electorProperties;

    @Mock
    private RedisLockRegistry lockRegistry;

    @Mock
    private ThreadPoolTaskScheduler taskScheduler;

    @Mock
    private DistributedLock lock;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    @Mock
    private HealthProbe healthProbe;

    private MutableClock clock;

    private ElectorService electorService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        electorService = new ElectorService(callbacks, electorProperties, lockRegistry, taskScheduler, healthProbe, clock);

        // Default to healthy so the existing (probe-agnostic) tests behave exactly as before; the
        // health-gate tests below override this per case.
        lenient()
                .when(healthProbe.isHealthy())
                .thenReturn(true);

        // Default property values (using lenient() for properties that may not be used in all tests)
        lenient()
                .when(electorProperties.getLockName())
                .thenReturn("test-lock");
        lenient()
                .when(electorProperties.getRetryPeriod())
                .thenReturn(Duration.ofSeconds(5));
        lenient()
                .when(electorProperties.getRenewDeadline())
                .thenReturn(Duration.ofSeconds(60));
        lenient()
                .when(electorProperties.getLeaseDuration())
                .thenReturn(Duration.ofSeconds(120));
        lenient()
                .when(electorProperties.getHealthProbeUnhealthyBackoff())
                .thenReturn(Duration.ofSeconds(30));

        // stop()/awaitLockRelease() submits the release onto taskScheduler and waits for it (the
        // real ThreadPoolTaskScheduler runs it there); the mock doesn't run anything by default, so
        // stub submit() to invoke the given task synchronously, matching real behavior closely
        // enough for these tests.
        lenient()
                .when(taskScheduler.submit(any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation
                            .<Runnable>getArgument(0)
                            .run();
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Test
    void start_shouldSetRunningAndScheduleLockLoop() {
        // When
        electorService.start();

        // Then
        assertTrue(electorService.isRunning());
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        // Every pod carries the label from boot, before it has contested any election.
        verify(callbacks).ensureSelfLabeled();
    }

    @Test
    void stop_shouldSetRunningToFalseAndReleaseLock() {
        // Given
        electorService.start();

        // When
        electorService.stop();

        // Then
        assertFalse(electorService.isRunning());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockLoop_shouldAcquireLockAndInvokeCallbacks() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();

        // Capture and execute the lockLoop runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then
        verify(lockRegistry).obtain("test-lock");
        verify(lock).tryLock(5L, TimeUnit.SECONDS);
        verify(callbacks).onLockAcquired();
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void lockLoop_shouldReleaseLockAndRetryWhenLockAcquiredCallbackFails() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new IllegalStateException("failed to label elected pod"))
                .when(callbacks)
                .onLockAcquired();

        electorService.start();

        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then
        verify(callbacks).onLockAcquired();
        verify(lock).unlock();
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void lockLoop_shouldRetryWhenLockNotAcquired() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        electorService.start();

        // Capture and execute the lockLoop runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then
        verify(lockRegistry).obtain("test-lock");
        verify(lock).tryLock(5L, TimeUnit.SECONDS);
        verify(callbacks, never()).onLockAcquired();
        // Should schedule retry
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void lockLoop_shouldHandleInterruptedException() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("Test interruption"));

        electorService.start();

        // Capture and execute the lockLoop runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then
        verify(callbacks, never()).onLockAcquired();
        assertTrue(Thread.interrupted()); // Verify interrupt flag is set
    }

    @Test
    void lockLoop_shouldRetryOnException() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new RuntimeException("Test exception"));

        electorService.start();

        // Capture and execute the lockLoop runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then
        verify(callbacks, never()).onLockAcquired();
        // Should schedule retry
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldRenewLock() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();

        // Capture lockLoop and execute to acquire lock
        final ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor
                .getValue()
                .run();

        // Capture refresh task
        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor
                .getValue()
                .run();

        // Then
        verify(lockRegistry).renewLock("test-lock", Duration.ofSeconds(120));
        // Self-heals any label a prior attempt failed to set, every renewal tick.
        verify(callbacks).reconcileLeaderLabels();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldRetryOnceThenSucceedOnTransientRenewFailure() throws Exception {
        // Given: the first renew attempt throws, the second (immediate retry) succeeds.
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);
        doThrow(new RuntimeException("Redis blip"))
                .doNothing()
                .when(lockRegistry)
                .renewLock(anyString(), any(Duration.class));

        electorService.start();

        final ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor
                .getValue()
                .run();

        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor
                .getValue()
                .run();

        // Then: a single transient failure does not cost leadership — it's absorbed by the retry.
        verify(lockRegistry, times(2)).renewLock("test-lock", Duration.ofSeconds(120));
        verify(callbacks, never()).onLockLost();
        verify(callbacks).reconcileLeaderLabels();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldHandleLockLostOnRenewFailure() throws Exception {
        // Given: both the initial renew attempt AND its immediate retry fail.
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);
        doThrow(new RuntimeException("Renew failed"))
                .when(lockRegistry)
                .renewLock(anyString(), any(Duration.class));

        electorService.start();

        // Capture lockLoop and execute to acquire lock
        final ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor
                .getValue()
                .run();

        // Capture refresh task
        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor
                .getValue()
                .run();

        // Then: the retry was attempted (two calls total) before giving up leadership.
        verify(lockRegistry, times(2)).renewLock("test-lock", Duration.ofSeconds(120));
        verify(callbacks).onLockLost();
        verify(scheduledFuture).cancel(true);
        verify(lock).unlock();
    }

    @Test
    void onDestroy_shouldCallStop() {
        // Given
        electorService.start();

        // When
        electorService.onDestroy();

        // Then
        assertFalse(electorService.isRunning());
    }

    @Test
    void lockLoop_shouldNotLeadWhenUnhealthyWithinDeadlockGrace() throws Exception {
        // Given: lock is free but this pod fails its health probe, and the grace has not elapsed
        when(healthProbe.isHealthy()).thenReturn(false);
        lenient()
                .when(electorProperties.getHealthProbeDeadlockGrace())
                .thenReturn(Duration.ofMinutes(5));
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then: it does not lead, releases the transiently-held lock, and re-probes — but on the
        // longer unhealthy backoff (30s), NOT the tight retryPeriod (5s). Re-grabbing the free lock
        // every retryPeriod is exactly the livelock that starves the healthy peers racing for it.
        verify(callbacks, never()).onLockAcquired();
        verify(lock).unlock();
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        final ArgumentCaptor<Instant> whenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), whenCaptor.capture());
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(t0.plus(Duration.ofSeconds(30)), whenCaptor
                .getAllValues()
                .get(1));
    }

    @Test
    void lockLoop_unhealthyFreeLock_whenReleaseFails_retriesSoonNotLongBackoff() throws Exception {
        // Given: unhealthy, lock free (acquired), within grace, but releasing it throws. We may still
        // hold the lock, so re-probe on the short retryPeriod (5s) to retry the release rather than
        // sit on the long unhealthy backoff (30s) while healthy peers stay blocked.
        when(healthProbe.isHealthy()).thenReturn(false);
        lenient()
                .when(electorProperties.getHealthProbeDeadlockGrace())
                .thenReturn(Duration.ofMinutes(5));
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        doThrow(new IllegalStateException("redis blip releasing lock"))
                .when(lock)
                .unlock();

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then: it re-probes on retryPeriod (5s), not the unhealthy backoff (30s).
        verify(callbacks, never()).onLockAcquired();
        verify(lock).unlock();
        final ArgumentCaptor<Instant> whenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), whenCaptor.capture());
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(t0.plus(Duration.ofSeconds(5)), whenCaptor
                .getAllValues()
                .get(1));
    }

    @Test
    void lockLoop_shouldBackOffNotTightRetryWhenUnhealthyAndLeaderExists() throws Exception {
        // Given: a leader already exists (lock not acquirable) and this pod is unhealthy.
        when(healthProbe.isHealthy()).thenReturn(false);
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then: an unhealthy pod has no business racing for leadership, so it re-probes on the
        // longer backoff (30s) rather than the retryPeriod (5s) a healthy pod would use here.
        verify(callbacks, never()).onLockAcquired();
        final ArgumentCaptor<Instant> whenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), whenCaptor.capture());
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(t0.plus(Duration.ofSeconds(30)), whenCaptor
                .getAllValues()
                .get(1));
    }

    @Test
    void lockLoop_shouldUseRetryPeriodWhenHealthyAndLeaderExists() throws Exception {
        // Given: healthy (default) and a leader already exists.
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then: a healthy contender keeps re-probing on the tight retryPeriod (5s) so it takes over
        // promptly the moment the lock frees — the backoff is only for unhealthy pods.
        final ArgumentCaptor<Instant> whenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), whenCaptor.capture());
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(t0.plus(Duration.ofSeconds(5)), whenCaptor
                .getAllValues()
                .get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockLoop_shouldBreakDeadlockAndLeadWhenUnhealthyBeyondGrace() throws Exception {
        // Given: unhealthy, but the deadlock grace is zero so the escape hatch fires immediately
        when(healthProbe.isHealthy()).thenReturn(false);
        when(electorProperties.getHealthProbeDeadlockGrace()).thenReturn(Duration.ZERO);
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor
                .getValue()
                .run();

        // Then: it leads (degraded) rather than deadlocking forever
        verify(callbacks).onLockAcquired();
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldRelinquishAfterConsecutiveUnhealthyProbes() throws Exception {
        // Given: healthy at acquisition, then unhealthy at the next refresh with threshold 1
        when(healthProbe.isHealthy()).thenReturn(true, false);
        when(electorProperties.getHealthProbeFailureThreshold()).thenReturn(1);
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();
        final ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor
                .getValue()
                .run();

        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor
                .getValue()
                .run();

        // Then: it relinquishes leadership instead of renewing
        verify(lockRegistry, never()).renewLock(anyString(), any(Duration.class));
        verify(callbacks).onLockLost();
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldTolerateTransientUnhealthyBelowThreshold() throws Exception {
        // Given: healthy at acquisition, one unhealthy refresh, threshold 3
        when(healthProbe.isHealthy()).thenReturn(true, false);
        when(electorProperties.getHealthProbeFailureThreshold()).thenReturn(3);
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();
        final ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor
                .getValue()
                .run();

        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor
                .getValue()
                .run();

        // Then: a single failure below the threshold keeps the lock (renews, does not relinquish)
        verify(lockRegistry).renewLock("test-lock", Duration.ofSeconds(120));
        verify(callbacks, never()).onLockLost();
        verify(callbacks).reconcileLeaderLabels();
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradedLeader_afterRelinquishing_doesNotImmediatelyReacquireWithinGrace() throws Exception {
        // Regression: with all pods unhealthy, the first leader breaks the deadlock once the grace
        // elapses, then relinquishes on an unhealthy refresh. The very next acquisition attempt must
        // start a FRESH grace window rather than re-leading on the stale timer — otherwise a single
        // unhealthy pod monopolises leadership, re-taking it the instant it gives it up.
        when(healthProbe.isHealthy()).thenReturn(false);
        when(electorProperties.getHealthProbeDeadlockGrace()).thenReturn(Duration.ofMinutes(5));
        when(electorProperties.getHealthProbeFailureThreshold()).thenReturn(1);
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();
        final ArgumentCaptor<Runnable> loopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(loopCaptor.capture(), any(Instant.class));
        final Runnable lockLoop = loopCaptor.getValue();

        // 1) First loop, still within grace: starts the deadlock timer but does not lead.
        lockLoop.run();
        verify(callbacks, never()).onLockAcquired();

        // 2) Grace elapses → next loop breaks the deadlock and leads (degraded).
        clock.advance(Duration.ofMinutes(6));
        lockLoop.run();
        verify(callbacks, times(1)).onLockAcquired();

        // Fire the refresh: unhealthy at threshold 1 → relinquish leadership.
        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));
        refreshCaptor
                .getValue()
                .run();
        verify(callbacks, times(1)).onLockLost();

        // 3) Immediate re-acquire attempt, still unhealthy, no time advanced: must NOT re-lead,
        //    proving the deadlock timer was reset on becoming leader (still only one acquisition).
        lockLoop.run();
        verify(callbacks, times(1)).onLockAcquired();
    }

    @Test
    void getPhase_shouldReturnIntegerMinValue() {
        // When
        final int phase = electorService.getPhase();

        // Then
        assertEquals(Integer.MIN_VALUE, phase);
    }

    @Test
    void releaseLockIfHeld_shouldHandleNullLock() {
        // When
        electorService.stop();

        // Then - should not throw exception
        verify(lock, never()).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseLockIfHeld_shouldHandleUnlockException() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);
        doThrow(new RuntimeException("Unlock failed"))
                .when(lock)
                .unlock();

        electorService.start();

        // Capture and execute lockLoop
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));
        runnableCaptor
                .getValue()
                .run();

        // When
        electorService.stop();

        // Then - should handle exception gracefully
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stop_shouldReleaseLockViaSchedulerThreadNotCallingThread() throws Exception {
        // DistributedLock.unlock() is thread-owned (RedisLockRegistry.RedisLock wraps a local
        // ReentrantLock); calling it directly from stop()'s caller thread would throw
        // IllegalStateException against a real lock. stop() must route the release through the
        // taskScheduler instead of calling releaseLockIfHeld() inline.
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class),
                                               any(Instant.class),
                                               any(Duration.class))).thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));
        runnableCaptor
                .getValue()
                .run();

        // When
        electorService.stop();

        // Then
        verify(taskScheduler).submit(any(Runnable.class));
        verify(lock).unlock();
    }

    @Test
    void stop_shouldNotThrowWhenReleaseTimesOut() {
        // Given: the scheduler never runs the submitted release task (simulating a wedged/backed-up
        // scheduler thread) — submit() returns a Future that never completes.
        when(taskScheduler.submit(any(Runnable.class))).thenReturn(new CompletableFuture<>());

        // When/Then: stop() must not throw or hang the caller past the bounded wait.
        electorService.start();
        electorService.stop();

        assertFalse(electorService.isRunning());
    }

    // A hand-advanceable clock so deadlock-grace timing can be tested deterministically.
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant start) {
            this.instant = start;
        }

        private void advance(final Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }
    }
}
