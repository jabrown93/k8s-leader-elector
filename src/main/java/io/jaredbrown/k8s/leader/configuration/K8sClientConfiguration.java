package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class K8sClientConfiguration {
    @Bean
    @PreDestroy
    public KubernetesClient kubernetesClient() {
        final KubernetesClientBuilder builder = new KubernetesClientBuilder();
        return builder.build();
    }

}
