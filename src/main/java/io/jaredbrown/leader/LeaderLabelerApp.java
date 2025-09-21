package io.jaredbrown.leader;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class LeaderLabelerApp {
    private static final String LEADER_LABEL_VAL_TRUE = "true";

    public static void main(final String[] args) throws Exception {
        // 1) Build client (in-cluster first, fall back to local kubeconfig)
        ApiClient client;
        try {
            client = ClientBuilder
                    .cluster()
                    .build();
        } catch (final Exception e) {
            // fallback for local testing
            final String kubeConfigPath = System.getProperty("kubeconfig",
                                                             System.getProperty("user.home") + "/.kube/config");
            client = ClientBuilder
                    .kubeconfig(KubeConfig.loadKubeConfig(new java.io.FileReader(kubeConfigPath)))
                    .build();
        }
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);

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
        final CoreV1Api core = new CoreV1Api();

        // 5) Hooks: label on start, remove on stop
        final Runnable onStartLeading = () -> {
            try {
                // JSON Merge Patch to add/update the label
                final String patch = "{ \"metadata\": { \"labels\": { \"" + labelKey + "\": \"" + LEADER_LABEL_VAL_TRUE + "\" } } }";
                core.patchNamespacedPod(podName, podNamespace, new V1Patch(patch));
                System.out.println("✅ Gained leadership. Labeled " + podName + " as leader.");
            } catch (final Exception e) {
                System.err.println("Failed to label pod as leader: " + e.getMessage());
                // If we cannot label, better to relinquish leadership to avoid split-brain
                throw new RuntimeException(e);
            }
        };

        final Runnable onStopLeading = () -> {
            try {
                // Use JSON Patch to remove the label safely (doesn't clobber other labels)
                final String jsonPatch = "[ { \"op\": \"remove\", \"path\": \"/metadata/labels/" + escapeJsonPointer(
                        labelKey) + "\" } ]";
                core.patchNamespacedPod(podName, podNamespace, new V1Patch(jsonPatch));
                System.out.println("🛑 Lost leadership. Removed leader label from " + podName + ".");
            } catch (final Exception e) {
                // If the label isn't there anymore or removal fails, just log and continue.
                System.err.println("Warning removing leader label (likely already gone): " + e.getMessage());
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

    // RFC6901 JSON Pointer escaping for label key in JSON Patch path
    private static String escapeJsonPointer(final String key) {
        return key
                .replace("~", "~0")
                .replace("/", "~1");
    }
}
