package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class K8sClientConfiguration {
    @Bean
    public KubernetesClient kubernetesClient() {
        final KubernetesClientBuilder builder = new KubernetesClientBuilder();
        return builder.build();
    }

}
