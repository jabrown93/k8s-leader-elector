package io.jaredbrown.k8s.leader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LeaderLabelerApp {

    public static void main(String[] args) {
        SpringApplication.run(LeaderLabelerApp.class, args);
    }

}
