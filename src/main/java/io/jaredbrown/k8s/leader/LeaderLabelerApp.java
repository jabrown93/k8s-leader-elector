package io.jaredbrown.k8s.leader;

import io.jaredbrown.k8s.leader.elector.ElectorService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.FileReader;
import java.io.IOException;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = "io.jaredbrown.k8s.leader")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaderLabelerApp {

    static void main(String[] args) {
        SpringApplication.run(LeaderLabelerApp.class, args);
    }

//    static void main() throws IOException {
//        ApiClient client;
//        try {
//            client = ClientBuilder
//                    .cluster()
//                    .build();
//        } catch (final Exception error) {
//            log.warn("Error while initializing KubernetesClient, using user kube config: {}", error.getMessage(), error);
//            final String kubeConfigPath = System.getProperty("kubeconfig",
//                                                             System.getProperty("user.home") + "/.kube/config");
//            client = ClientBuilder
//                    .kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath)))
//                    .build();
//        }
//        final ApiClient apiClient = client;
//        Configuration.setDefaultApiClient(client);
//        final ElectorService electorService = new ElectorService(apiClient);
//        electorService.start();
//
//    }
}
