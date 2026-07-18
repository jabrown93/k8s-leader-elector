package io.jaredbrown.k8s.leader.elector;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for the leader-election sidecar (prefix {@code elector}).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "elector")
public class ElectorProperties {
    /** Label key set to {@code true} on the leader Pod and {@code false} on every other pod. */
    @NotBlank(message = "elector.labelKey must be configured")
    private String labelKey;

    /** Name of the distributed lock in Redis; the registry key is {@code <lockName>-lock-registry}. */
    @NotBlank(message = "elector.lockName must be configured")
    private String lockName;

    /** Label key used to select the pods this elector labels and reconciles. */
    @NotBlank(message = "elector.selectorName must be configured")
    private String selectorLabelKey;

    /** Label value paired with {@link #selectorLabelKey} to select the pods this elector reconciles. */
    @NotBlank(message = "elector.selectorValue must be configured")
    private String selectorLabelValue;

    /** Lock TTL in Redis; the lock expires if not renewed within this window. */
    @NotNull
    @DurationMin(seconds = 1, message = "elector.leaseDuration must be at least 1s")
    private Duration leaseDuration = Duration.ofSeconds(120);

    /** How often the leader renews the lock and reconciles leader labels. */
    @NotNull
    @DurationMin(seconds = 1, message = "elector.renewDeadline must be at least 1s")
    private Duration renewDeadline = Duration.ofSeconds(60);

    /** How often a non-leader retries lock acquisition. */
    @NotNull
    @DurationMin(seconds = 1, message = "elector.retryPeriod must be at least 1s")
    private Duration retryPeriod = Duration.ofSeconds(5);

    // --- Optional health probe ---------------------------------------------------------------
    // When enabled, a pod must pass a health probe to be eligible to acquire (and to keep)
    // leadership. The probe is intentionally generic: the application writes its own notion of
    // "fit to lead" to a status file (typically on a shared emptyDir) and the elector only reads
    // it. This keeps application logic out of the elector and adds no tools to its image.
    // Disabled by default so existing consumers are unaffected.

    /**
     * Gates leadership on the health probe. When {@code false}, the probe is never read and
     * behavior is identical to a probe-less elector.
     */
    private boolean healthProbeEnabled = false;

    /** Path to the status file the application maintains. Missing/unreadable file = unhealthy. */
    private String healthProbeFilePath;

    /** The (trimmed) file content that means healthy. Anything else = unhealthy. */
    private String healthProbeHealthyContent = "healthy";

    /**
     * Rejects the file if it has not been updated within this window, so a dead writer reads as
     * unhealthy rather than stale-healthy. Set to zero to disable the freshness check.
     */
    @NotNull
    private Duration healthProbeMaxAge = Duration.ofMinutes(2);

    /**
     * Consecutive probe failures tolerated while already leader before relinquishing. Absorbs a
     * transient blip or a normal gravity rebuild without flapping leadership. Must be {@code >= 1}
     * so a single probe failure cannot immediately demote the leader (0/negative would be
     * nonsensical).
     */
    @Min(value = 1, message = "elector.healthProbeFailureThreshold must be at least 1")
    private int healthProbeFailureThreshold = 3;

    /**
     * If the lock is free but no pod is healthy, leadership would deadlock forever (e.g. a fresh
     * install or an all-pods-wiped state). After the lock has been observed
     * acquirable-but-this-pod-unhealthy for this long, acquire it anyway and lead in a degraded
     * state. Zero is a valid, deliberate value (skip the grace window and break the deadlock
     * immediately); only negative values - which would silently behave identically to zero - are
     * rejected as almost certainly a typo.
     */
    @NotNull
    @DurationMin(seconds = 0, message = "elector.healthProbeDeadlockGrace must not be negative")
    private Duration healthProbeDeadlockGrace = Duration.ofMinutes(5);

    /**
     * How long an unhealthy pod backs off before re-probing for leadership, instead of the tight
     * {@link #retryPeriod} a healthy pod uses. An unhealthy ex-leader that keeps re-acquiring the
     * free lock every {@code retryPeriod} (to test eligibility) and releasing it starves the
     * healthy peers that are trying to take over — a livelock that leaves the deployment
     * leaderless. Backing off well past {@code retryPeriod} gives healthy peers uncontested
     * acquisition windows so one of them leads promptly. Should comfortably exceed
     * {@code retryPeriod}; well under {@link #healthProbeDeadlockGrace} so the all-unhealthy
     * escape hatch still fires on time (the unhealthy pod still re-probes ~grace/backoff times).
     */
    @NotNull
    @DurationMin(seconds = 1, message = "elector.healthProbeUnhealthyBackoff must be at least 1s")
    private Duration healthProbeUnhealthyBackoff = Duration.ofSeconds(30);
}
