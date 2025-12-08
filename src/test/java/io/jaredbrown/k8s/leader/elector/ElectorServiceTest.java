package io.jaredbrown.k8s.leader.elector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.integration.support.locks.DistributedLock;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElectorServiceTest {

    @Mock
    private LockCallbacks callbacks;

    @Mock
    private ElectorProperties electorProperties;

    @Mock
    private RedisLockRegistry lockRegistry;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private DistributedLock lock;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private ElectorService electorService;

    @BeforeEach
    void setUp() {
        electorService = new ElectorService(callbacks, electorProperties, lockRegistry, taskScheduler);

        // Default property values (using lenient() for properties that may not be used in all tests)
        lenient().when(electorProperties.getLockName()).thenReturn("test-lock");
        lenient().when(electorProperties.getRetryPeriod()).thenReturn(Duration.ofSeconds(5));
        lenient().when(electorProperties.getRenewDeadline()).thenReturn(Duration.ofSeconds(60));
        lenient().when(electorProperties.getLeaseDuration()).thenReturn(Duration.ofSeconds(120));
    }

    @Test
    void start_shouldSetRunningAndScheduleLockLoop() {
        // When
        electorService.start();

        // Then
        assertTrue(electorService.isRunning());
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
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
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();

        // Capture and execute the lockLoop runnable
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // When
        runnableCaptor.getValue().run();

        // Then
        verify(lockRegistry).obtain("test-lock");
        verify(lock).tryLock(5L, TimeUnit.SECONDS);
        verify(callbacks).onLockAcquired();
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(60)));
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
        runnableCaptor.getValue().run();

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
        runnableCaptor.getValue().run();

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
        runnableCaptor.getValue().run();

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
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenReturn((ScheduledFuture) scheduledFuture);

        electorService.start();

        // Capture lockLoop and execute to acquire lock
        ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor.getValue().run();

        // Capture refresh task
        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor.getValue().run();

        // Then
        verify(lockRegistry).renewLock("test-lock", Duration.ofSeconds(120));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshLock_shouldHandleLockLostOnRenewFailure() throws Exception {
        // Given
        when(lockRegistry.obtain("test-lock")).thenReturn(lock);
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenReturn((ScheduledFuture) scheduledFuture);
        doThrow(new RuntimeException("Renew failed")).when(lockRegistry)
            .renewLock(anyString(), any(Duration.class));

        electorService.start();

        // Capture lockLoop and execute to acquire lock
        ArgumentCaptor<Runnable> lockLoopCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(lockLoopCaptor.capture(), any(Instant.class));
        lockLoopCaptor.getValue().run();

        // Capture refresh task
        final ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleAtFixedRate(refreshCaptor.capture(), any(Instant.class), any(Duration.class));

        // When
        refreshCaptor.getValue().run();

        // Then
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
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenReturn((ScheduledFuture) scheduledFuture);
        doThrow(new RuntimeException("Unlock failed")).when(lock).unlock();

        electorService.start();

        // Capture and execute lockLoop
        final ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));
        runnableCaptor.getValue().run();

        // When
        electorService.stop();

        // Then - should handle exception gracefully
        verify(lock).unlock();
    }
}
