package io.jaredbrown.k8s.leader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Boots the leader-election sidecar's Spring context.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * @param args standard Spring Boot command-line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
