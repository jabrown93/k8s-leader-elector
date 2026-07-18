package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link KubernetesClient} bean with tightened per-request bounds.
 *
 * <p>fabric8 defaults to a 10s per-request timeout and up to 10 retries
 * ({@code Config.DEFAULT_REQUEST_TIMEOUT} / {@code DEFAULT_REQUEST_RETRY_BACKOFFLIMIT}).
 * {@code ElectorService} drives every Kubernetes call inline on its single scheduler thread, so an
 * unbounded call would (a) block the shutdown-time lock release past its 5s window
 * ({@code RELEASE_TIMEOUT}) and (b) in the extreme, stall lock renewal past the lease while a label
 * reconcile is in flight. Bounding both keeps a whole leader-label reconcile of a handful of pods
 * comfortably inside the release window and the lease, so the scheduler thread stays responsive.
 */
@Configuration
public class K8sClientConfiguration {
    private static final int REQUEST_TIMEOUT_MILLIS = 2000;
    private static final int REQUEST_RETRY_BACKOFF_LIMIT = 1;

    /**
     * @return a {@link KubernetesClient} built from the environment-derived config (in-cluster
     * service-account token, API server, CA, namespace) with only the request-timeout and retry
     * bounds overridden, so authentication is untouched
     */
    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        final Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout(REQUEST_TIMEOUT_MILLIS)
                .withRequestRetryBackoffLimit(REQUEST_RETRY_BACKOFF_LIMIT)
                .build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }
}
