package io.jaredbrown.k8s.leader.elector;

import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether this pod is fit to lead by reading a status file the application maintains.
 *
 * <p>The elector stays generic and tool-free: the application (e.g. on a shared {@code emptyDir})
 * writes "healthy"/"unhealthy" to a file and keeps its modification time fresh; the elector only
 * reads it. A missing, stale, or non-healthy file is reported as unhealthy. When the probe is
 * disabled the pod is always considered healthy, so a probe-less elector is unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthProbe {
    @Nonnull
    private final ElectorProperties electorProperties;

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
            if (!Files.isReadable(path)) {
                log.warn("Health status file {} is missing or unreadable; reporting unhealthy", filePath);
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

            final String content = Files
                    .readString(path)
                    .trim();
            final boolean healthy = content.equals(electorProperties.getHealthProbeHealthyContent());
            if (!healthy) {
                log.warn("Health status file {} content '{}' != '{}'; reporting unhealthy",
                         filePath,
                         content,
                         electorProperties.getHealthProbeHealthyContent());
            }
            return healthy;
        } catch (final IOException e) {
            log.error("Failed to read health status file {}; reporting unhealthy", filePath, e);
            return false;
        }
    }
}
