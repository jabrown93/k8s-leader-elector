package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class K8sClientConfiguration {
    // fabric8 defaults to a 10s per-request timeout and up to 10 retries
    // (Config.DEFAULT_REQUEST_TIMEOUT / DEFAULT_REQUEST_RETRY_BACKOFFLIMIT). ElectorService drives
    // every Kubernetes call inline on its single scheduler thread, so an unbounded call would
    // (a) block the shutdown-time lock release past its 5s window (RELEASE_TIMEOUT) and (b) in the
    // extreme, stall lock renewal past the lease while a label reconcile is in flight. Bound both so a
    // whole leader-label reconcile of a handful of pods finishes well inside the release window and
    // the lease, keeping the scheduler thread responsive.
    private static final int REQUEST_TIMEOUT_MILLIS = 2500;
    private static final int REQUEST_RETRY_BACKOFF_LIMIT = 3;

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        // Start from the environment-derived config (in-cluster service-account token, API server, CA,
        // namespace) so auth is untouched, then override only the request bounds.
        final Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout(REQUEST_TIMEOUT_MILLIS)
                .withRequestRetryBackoffLimit(REQUEST_RETRY_BACKOFF_LIMIT)
                .build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }
}
