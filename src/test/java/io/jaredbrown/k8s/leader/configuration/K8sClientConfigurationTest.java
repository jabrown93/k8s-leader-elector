package io.jaredbrown.k8s.leader.configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sClientConfigurationTest {

    @Test
    void kubernetesClient_shouldBoundRequestTimeoutAndRetries() {
        // Guards the bounds against a silent revert to fabric8's 10s/10-retry defaults; see
        // K8sClientConfiguration's class comment for why they matter.
        try (KubernetesClient client = new K8sClientConfiguration().kubernetesClient()) {
            assertEquals(2000, client.getConfiguration().getRequestTimeout());
            assertEquals(1, client.getConfiguration().getRequestRetryBackoffLimit());
        }
    }
}
