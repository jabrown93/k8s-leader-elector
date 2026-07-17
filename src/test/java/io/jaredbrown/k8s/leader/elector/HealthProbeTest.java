package io.jaredbrown.k8s.leader.elector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthProbeTest {

    @Mock
    private ElectorProperties electorProperties;

    @TempDir
    private Path tempDir;

    @Test
    void isHealthy_returnsTrueWhenProbeDisabled() {
        when(electorProperties.isHealthProbeEnabled()).thenReturn(false);

        assertTrue(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsTrueWhenFileFreshAndHealthy() throws IOException {
        final Path file = writeStatus("healthy");
        enabled(file, Duration.ofMinutes(2));

        assertTrue(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_trimsContent() throws IOException {
        final Path file = writeStatus("  healthy\n");
        enabled(file, Duration.ofMinutes(2));

        assertTrue(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsFalseWhenContentNotHealthy() throws IOException {
        final Path file = writeStatus("unhealthy");
        enabled(file, Duration.ofMinutes(2));

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsFalseWhenFileMissing() {
        enabled(tempDir.resolve("does-not-exist"), Duration.ofMinutes(2));

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsFalseWhenFileStale() throws IOException {
        final Path file = writeStatus("healthy");
        Files.setLastModifiedTime(file, FileTime.from(Instant
                                                              .now()
                                                              .minus(Duration.ofMinutes(10))));
        enabled(file, Duration.ofMinutes(2));

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_ignoresStalenessWhenMaxAgeZero() throws IOException {
        final Path file = writeStatus("healthy");
        Files.setLastModifiedTime(file, FileTime.from(Instant
                                                              .now()
                                                              .minus(Duration.ofMinutes(10))));
        enabled(file, Duration.ZERO);

        assertTrue(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsFalseWhenPathIsDirectory() throws IOException {
        // A directory is rejected by the isRegularFile() guard before any read is attempted.
        final Path directory = Files.createDirectory(tempDir.resolve("not-a-file"));
        enabled(directory, Duration.ZERO);

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    void isHealthy_returnsFalseWhenPathIsSymlink() throws IOException {
        // isRegularFile(path, NOFOLLOW_LINKS) rejects the symlink itself, even when its target is a
        // fresh, healthy regular file — this prevents a symlink planted at the configured path from
        // redirecting the read to an arbitrary file the elector process can access.
        final Path target = writeStatus("healthy");
        final Path symlink = Files.createSymbolicLink(tempDir.resolve("status-link"), target);
        enabled(symlink, Duration.ofMinutes(2));

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Timeout(5)
    void isHealthy_returnsFalseWhenPathIsFifoWithoutBlocking() throws IOException, InterruptedException {
        // A FIFO passes a naive isReadable()/mtime check and blocks indefinitely on open for
        // reading if no writer is attached. isRegularFile() must reject it before any read is
        // attempted, so this call returns promptly instead of wedging the caller's thread.
        final Path fifo = tempDir.resolve("status-fifo");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo
                .toAbsolutePath()
                .toString())
                .inheritIO()
                .start()
                .waitFor());
        enabled(fifo, Duration.ofMinutes(2));

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Timeout(30)
    void isHealthy_neverBlocksBeyondReadTimeoutUnderFileTypeRace() throws IOException, InterruptedException {
        // Regression test for the TOCTOU window between the isRegularFile() check and the read: a
        // co-located writer can atomically replace the file with a FIFO after the check passes but
        // before Files.readString() opens it. A background thread ping-pongs the path between a
        // regular file and a FIFO via fast atomic renames (fast enough to land inside that window
        // at least sometimes) while the foreground repeatedly calls isHealthy() and asserts every
        // call still returns promptly — proving the bounded read, not just the upfront check, is
        // what keeps the caller from being wedged.
        final Path path = tempDir.resolve("status-race");
        final Path regularSource = tempDir.resolve("regular-source");
        final Path fifoSource = tempDir.resolve("fifo-source");
        Files.writeString(regularSource, "healthy");
        assertEquals(0, new ProcessBuilder("mkfifo", fifoSource
                .toAbsolutePath()
                .toString())
                .inheritIO()
                .start()
                .waitFor());
        Files.move(regularSource, path);
        enabled(path, Duration.ofMinutes(2));
        final HealthProbe healthProbe = new HealthProbe(electorProperties);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final Thread swapper = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Files.move(path, regularSource, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(regularSource, path, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(path, fifoSource, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(fifoSource, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (final IOException ignored) {
                    // The foreground thread may observe `path` transiently missing mid-swap; retry.
                }
            }
        });
        swapper.setDaemon(true);
        swapper.start();
        try {
            for (int i = 0; i < 10; i++) {
                final long startNanos = System.nanoTime();
                healthProbe.isHealthy();
                final long elapsedMillis = Duration
                        .ofNanos(System.nanoTime() - startNanos)
                        .toMillis();
                assertTrue(elapsedMillis < 3000,
                           "isHealthy() took " + elapsedMillis + "ms, exceeding the bounded read timeout");
            }
        } finally {
            stop.set(true);
            swapper.join();
        }
    }

    @Test
    void isHealthy_returnsFalseWhenPathUnset() {
        when(electorProperties.isHealthProbeEnabled()).thenReturn(true);
        lenient()
                .when(electorProperties.getHealthProbeFilePath())
                .thenReturn(null);

        assertFalse(new HealthProbe(electorProperties).isHealthy());
    }

    private Path writeStatus(final String content) throws IOException {
        final Path file = tempDir.resolve("status");
        Files.writeString(file, content);
        return file;
    }

    private void enabled(final Path file, final Duration maxAge) {
        when(electorProperties.isHealthProbeEnabled()).thenReturn(true);
        when(electorProperties.getHealthProbeFilePath()).thenReturn(file.toString());
        lenient()
                .when(electorProperties.getHealthProbeMaxAge())
                .thenReturn(maxAge);
        lenient()
                .when(electorProperties.getHealthProbeHealthyContent())
                .thenReturn("healthy");
    }
}
