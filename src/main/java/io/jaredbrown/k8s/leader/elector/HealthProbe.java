package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether this pod is fit to lead by reading a status file the application maintains.
 *
 * <p>The elector stays generic and tool-free: the application (e.g. on a shared {@code emptyDir})
 * writes "healthy"/"unhealthy" to a file and keeps its modification time fresh; the elector only
 * reads it. A missing, stale, or non-healthy file is reported as unhealthy. When the probe is
 * disabled the pod is always considered healthy, so a probe-less elector is unchanged.
 *
 * <p><b>Writer contract:</b> {@link #isHealthy()} checks file type, readability, freshness, and
 * content in separate filesystem calls, not one atomic read. The writer must therefore write to a
 * temporary file in the same directory and atomically rename it into place (e.g.
 * {@code Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)}), never write the target path
 * in place - otherwise this probe can observe a partially-written file and report a spurious,
 * transient "unhealthy".
 *
 * <p><b>File type:</b> only a regular file (not a symlink) is accepted — anything else, including
 * a FIFO, socket, device file, directory, or symlink, is reported unhealthy without being opened.
 * This keeps a symlink from redirecting the read to an arbitrary file the elector process can
 * access. On a content mismatch, only a fixed message is logged, never the file's raw content, so
 * this probe cannot be used to exfiltrate file contents into application logs.
 *
 * <p><b>Read timeout:</b> the file-type check and the read are two separate filesystem calls, so a
 * co-located writer that replaces the file with a FIFO in between can still route the read into a
 * blocking {@code open()}. The read therefore runs on a single background thread bounded by
 * {@link #readTimeout}, so a blocked open can never wedge the caller — only that background
 * thread, which is abandoned (not interrupted, since a blocking FIFO open is not interruptible). A
 * timed-out read replaces the executor with a fresh one, so a later call - once the path is a
 * normal file again - gets a usable thread instead of queuing forever behind the abandoned task.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthProbe {
    @Nonnull
    private final ElectorProperties electorProperties;

    private Duration readTimeout = Duration.ofSeconds(2);

    private volatile ExecutorService fileReadExecutor = newFileReadExecutor();

    /**
     * @return a fresh single-thread daemon executor dedicated to the bounded file read described
     * in this class's read-timeout note
     */
    private static ExecutorService newFileReadExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "health-probe-file-read");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Stops the background read executor on application shutdown. */
    @PreDestroy
    void shutdown() {
        fileReadExecutor.shutdownNow();
    }

    /**
     * @return {@code true} if probing is disabled, or the status file exists, is fresh enough, and
     * its trimmed content matches the configured healthy value. Never throws — any problem reading
     * the file is reported as unhealthy so callers can treat it as a simple boolean gate.
     */
    public boolean isHealthy() {
        if (!electorProperties.isHealthProbeEnabled()) {
            return true;
        }

        final String filePath = electorProperties.getHealthProbeFilePath();
        if (filePath == null || filePath.isBlank()) {
            log.error("Health probe enabled but elector.healthProbeFilePath is not set; reporting unhealthy");
            return false;
        }

        final Path path = Path.of(filePath);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Health status file {} is missing or not a regular file; reporting unhealthy", filePath);
                return false;
            }

            if (!Files.isReadable(path)) {
                log.warn("Health status file {} is not readable; reporting unhealthy", filePath);
                return false;
            }

            final Duration maxAge = electorProperties.getHealthProbeMaxAge();
            if (maxAge != null && !maxAge.isZero() && !maxAge.isNegative()) {
                final Instant modified = Files
                        .getLastModifiedTime(path)
                        .toInstant();
                final Duration age = Duration.between(modified, Instant.now());
                if (age.compareTo(maxAge) > 0) {
                    log.warn("Health status file {} is stale (age {} > max {}); reporting unhealthy",
                             filePath,
                             age,
                             maxAge);
                    return false;
                }
            }

            final String content;
            try {
                content = fileReadExecutor
                        .submit(() -> Files.readString(path))
                        .get(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                        .trim();
            } catch (final TimeoutException e) {
                // The submitted task is likely still blocked forever (e.g. an unopened FIFO) and
                // this executor's single thread can never run anything else. Replace it so the next
                // call gets a usable thread instead of queuing behind a task that will never finish.
                final ExecutorService staleExecutor = fileReadExecutor;
                fileReadExecutor = newFileReadExecutor();
                staleExecutor.shutdownNow();
                log.error("Timed out after {} reading health status file {}; reporting unhealthy",
                          readTimeout,
                          filePath);
                return false;
            } catch (final ExecutionException e) {
                log.error("Failed to read health status file {}; reporting unhealthy", filePath, e.getCause());
                return false;
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                log.error("Interrupted while reading health status file {}; reporting unhealthy", filePath);
                return false;
            }

            final boolean healthy = content.equals(electorProperties.getHealthProbeHealthyContent());
            if (!healthy) {
                log.warn("Health status file {} content did not match the configured healthy value; "
                         + "reporting unhealthy", filePath);
            }
            return healthy;
        } catch (final IOException e) {
            log.error("Failed to read health status file {}; reporting unhealthy", filePath, e);
            return false;
        }
    }
}
