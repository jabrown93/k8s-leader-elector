package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sClientConfigurationTest {

    @Test
    void kubernetesClient_shouldBoundRequestTimeoutAndRetries() {
        // fabric8 defaults to a 10s request timeout and up to 10 retries; every K8s call runs
        // inline on ElectorService's single scheduler thread, so these must stay tightly bounded
        // (see K8sClientConfiguration's class comment) or a slow API server could stall lock
        // renewal past the lease or the shutdown-time release past its window.
        try (KubernetesClient client = new K8sClientConfiguration().kubernetesClient()) {
            assertEquals(2000, client.getConfiguration().getRequestTimeout());
            assertEquals(1, client.getConfiguration().getRequestRetryBackoffLimit());
        }
    }
}
