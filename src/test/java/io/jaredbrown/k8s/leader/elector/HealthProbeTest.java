package io.jaredbrown.k8s.leader.elector;

import org.junit.jupiter.api.Test;
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
    void isHealthy_returnsFalseWhenReadThrows() throws IOException {
        // A directory passes the isReadable() and freshness checks but Files.readString() throws
        // IOException on it, exercising the catch block that reports unhealthy rather than
        // propagating (e.g. the status file is deleted or replaced by a directory mid-read).
        final Path directory = Files.createDirectory(tempDir.resolve("not-a-file"));
        enabled(directory, Duration.ZERO);

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
