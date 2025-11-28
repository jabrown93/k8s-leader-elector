package io.jaredbrown.k8s.leader;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = "io.jaredbrown.k8s.leader")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaderLabelerApp {

    static void main(final String[] args) {
        SpringApplication.run(LeaderLabelerApp.class, args);
    }

}
