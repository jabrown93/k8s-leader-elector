package io.jaredbrown.k8s.leader.elector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@ConfigurationProperties(prefix = "elector")
public class ElectorProperties {
    @NotBlank(message = "elector.labelKey must be configured")
    private String labelKey;

    @NotBlank(message = "elector.lockName must be configured")
    private String lockName;

    @NotBlank(message = "elector.selectorName must be configured")
    private String selectorLabelKey;

    @NotBlank(message = "elector.selectorValue must be configured")
    private String selectorLabelValue;

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(120);

    @NotNull
    private Duration renewDeadline = Duration.ofSeconds(60);

    @NotNull
    private Duration retryPeriod = Duration.ofSeconds(5);

    // --- Optional health probe ---------------------------------------------------------------
    // When enabled, a pod must pass a health probe to be eligible to acquire (and to keep)
    // leadership. The probe is intentionally generic: the application writes its own notion of
    // "fit to lead" to a status file (typically on a shared emptyDir) and the elector only reads
    // it. This keeps application logic out of the elector and adds no tools to its image.
    // Disabled by default so existing consumers are unaffected.

    // Gate leadership on the health probe. When false, the probe is never read and behavior is
    // identical to a probe-less elector.
    private boolean healthProbeEnabled = false;

    // Path to the status file the application maintains. Missing/unreadable file = unhealthy.
    private String healthProbeFilePath;

    // The (trimmed) file content that means healthy. Anything else = unhealthy.
    private String healthProbeHealthyContent = "healthy";

    // Reject the file if it has not been updated within this window, so a dead writer reads as
    // unhealthy rather than stale-healthy. Set to zero to disable the freshness check.
    @NotNull
    private Duration healthProbeMaxAge = Duration.ofMinutes(2);

    // Consecutive probe failures tolerated while already leader before relinquishing. Absorbs a
    // transient blip or a normal gravity rebuild without flapping leadership.
    private int healthProbeFailureThreshold = 3;

    // If the lock is free but no pod is healthy, leadership would deadlock forever (e.g. a fresh
    // install or an all-pods-wiped state). After the lock has been observed acquirable-but-this-
    // pod-unhealthy for this long, acquire it anyway and lead in a degraded state.
    @NotNull
    private Duration healthProbeDeadlockGrace = Duration.ofMinutes(5);
}
