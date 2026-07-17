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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

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
