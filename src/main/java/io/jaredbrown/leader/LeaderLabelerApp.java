package io.jaredbrown.leader;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.PatchUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;

@Slf4j
public class LeaderLabelerApp {

    static void main() throws IOException {
        ApiClient client;
        try {
            client = ClientBuilder
                    .cluster()
                    .build();
        } catch (final Exception e) {
            final String kubeConfigPath = System.getProperty("kubeconfig",
                                                             System.getProperty("user.home") + "/.kube/config");
            client = ClientBuilder
                    .kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath)))
                    .build();
        }
        final ApiClient apiClient = client;
        Configuration.setDefaultApiClient(client);

        // 2) Resolve identity and namespace from Downward API
        final String podName = envOrThrow("POD_NAME");
        final String podNamespace = envOrThrow("POD_NAMESPACE");
        final String labelKey = envOrThrow("LEADER_LABEL_KEY");

        // 3) Construct a LeaseLock (best practice vs ConfigMap/Endpoints)
        //    "leader-election" is a stable name for the Lease object; change as needed.
        final String leaseName = System.getProperty("LEASE_NAME", "leader-election");
        final String leaseNamespace = System.getProperty("LEASE_NAMESPACE", podNamespace);

        // 4) Configure timings (tune for your needs)
        final Duration leaseDuration = Duration.ofSeconds(120);   // total lease time
        final Duration renewDeadline = Duration.ofSeconds(30);   // must renew within this window
        final Duration retryPeriod = Duration.ofSeconds(5);    // retry interval

        final LeaseLock lock = new LeaseLock(leaseNamespace, leaseName, podName, client);
        final LeaderElectionConfig config = new LeaderElectionConfig(lock, leaseDuration, renewDeadline, retryPeriod);
        final CoreV1Api core = new CoreV1Api(client);

        // 5) Hooks: label on start, remove on stop
        final Runnable onStartLeading = () -> {
            try {
                // JSON Merge Patch to add/update the label
                final String body = "{ \"metadata\": { \"labels\": { \"" + labelKey + "\": \"true\" } } }";

                PatchUtils.patch(V1Pod.class,
                                 () -> core
                                         .patchNamespacedPod(podName, podNamespace, new V1Patch(body))
                                         .buildCall(null),
                                 V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                                 apiClient);

                log.info("✅ Gained leadership. Labeled " + podName + " as leader.");
            } catch (final Exception e) {
                log.error("Failed to label pod as leader: " + e.getMessage());
                // If we cannot label, better to relinquish leadership to avoid split-brain
                throw new IllegalStateException(e);
            }
        };

        final Runnable onStopLeading = () -> {
            try {
                final String body = "{ \"metadata\": { \"labels\": { \"" + labelKey + "\": \"false\" } } }";

                PatchUtils.patch(V1Pod.class,
                                 () -> core
                                         .patchNamespacedPod(podName, podNamespace, new V1Patch(body))
                                         .buildCall(null),
                                 V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                                 apiClient);
                log.info("🛑 Lost leadership. Set leader label on " + podName + " to false.");
            } catch (final Exception e) {
                log.error("Failed to unlabel pod as leader: " + e.getMessage());
            }
        };

        // 6) Run leader election in the foreground (blocks)
        //    If you prefer non-blocking, spawn on an executor.
        try (final LeaderElector elector = new LeaderElector(config)) {
            elector.run(onStartLeading, onStopLeading);
        }
    }

    private static String envOrThrow(final String key) {
        final String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing env var: " + key);
        }
        return v;
    }
}
