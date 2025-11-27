package io.jaredbrown.k8s.leader.elector;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@Slf4j
@ConfigurationProperties(prefix = "elector")
public class ElectorProperties {
    private String labelKey;
    private String lockName;
    private String appName;
    private Duration leaseDuration = Duration.ofSeconds(120);
    private Duration renewDeadline = Duration.ofSeconds(60);
    private Duration retryPeriod = Duration.ofSeconds(5);
}
