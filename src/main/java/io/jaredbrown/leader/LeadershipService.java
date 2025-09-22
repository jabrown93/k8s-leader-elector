package io.jaredbrown.leader;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.PatchUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class LeadershipService {
    private final ApiClient client;
    private final CoreV1Api coreV1Api;
    private final String podName;
    private final String podNamespace;
    private final String labelKey;
    private final String leaseName;
    private final String leaseNamespace;

    private boolean leader;

    public LeadershipService(final ApiClient client) {
        this.client = client;
        this.coreV1Api = new CoreV1Api(client);

        // 2) Resolve identity and namespace from Downward API
        this.podName = envOrThrow("POD_NAME");
        this.podNamespace = envOrThrow("POD_NAMESPACE");
        this.labelKey = envOrThrow("LEADER_LABEL_KEY");

        // 3) Construct a LeaseLock (best practice vs ConfigMap/Endpoints)
        //    "leader-election" is a stable name for the Lease object; change as needed.
        this.leaseName = System.getProperty("LEASE_NAME", "leader-election");
        this.leaseNamespace = System.getProperty("LEASE_NAMESPACE", podNamespace);

    }

    public void start() {
        // 4) Configure timings (tune for your needs)
        final Duration leaseDuration = Duration.ofSeconds(120);   // total lease time
        final Duration renewDeadline = Duration.ofSeconds(30);   // must renew within this window
        final Duration retryPeriod = Duration.ofSeconds(5);    // retry interval

        final LeaseLock lock = new LeaseLock(leaseNamespace, leaseName, podName, client);
        final LeaderElectionConfig config = new LeaderElectionConfig(lock, leaseDuration, renewDeadline, retryPeriod);
        // 5) Hooks: label on start, remove on stop
        final Runnable onStartLeading = () -> {
            try {
                this.leader = true;
                log.info("✅ Gained leadership. Updating label");
                upsertLabel();
            } catch (final Exception e) {
                log.error("Failed to label pod as leader: {}", e.getMessage(), e);
                // If we cannot label, better to relinquish leadership to avoid split-brain
                throw new IllegalStateException(e);
            }
        };

        final Runnable onStopLeading = () -> {
            try {
                this.leader = false;
                log.info("🛑 Lost leadership. Updating label");
                upsertLabel();
            } catch (final Exception e) {
                log.error("Failed to unlabel pod as leader: {}", e.getMessage(), e);
            }
        };

        // 6) Run leader election in the foreground (blocks)
        //    If you prefer non-blocking, spawn on an executor.
        while (true) {
            log.info("Attempting to acquire leadership on node: " + podName);
            try (final LeaderElector elector = new LeaderElector(config)) {
                elector.run(onStartLeading, onStopLeading);
            } catch (final RuntimeException e) {
                log.error("Leader election error: {}", e.getMessage(), e);
            }

        }
    }

    private void upsertLabel() {
        try {
            final String body = "{ \"metadata\": { \"labels\": { \"" + labelKey + "\": \"" + this.leader + "\" } } }";

            PatchUtils.patch(V1Pod.class,
                             () -> coreV1Api
                                     .patchNamespacedPod(podName, podNamespace, new V1Patch(body))
                                     .buildCall(null),
                             V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                             client);
            log.info("Labeled " + podName + " with " + labelKey + "=" + this.leader);
        } catch (final Exception e) {
            log.error("Failed to upsert leader label: {}", e.getMessage(), e);
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
