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

    @NotBlank(message = "elector.appName must be configured")
    private String appName;

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(120);

    @NotNull
    private Duration renewDeadline = Duration.ofSeconds(60);

    @NotNull
    private Duration retryPeriod = Duration.ofSeconds(5);
}
