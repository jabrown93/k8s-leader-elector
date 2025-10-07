package io.jaredbrown.k8s.leader;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

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

    private static final long MIN_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 5_000L;

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
        final Duration leaseDuration = Duration.ofSeconds(60);   // total lease time
        final Duration renewDeadline = Duration.ofSeconds(15);   // must renew within this window
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

        // Consumer invoked when a new leader is observed. Ensure we unlabel ourselves if it's not us.
        final Consumer<String> newLeaderConsumer = newLeaderId -> {
            try {
                // If we observe a different leader and we still consider ourselves leader, unlabel.
                if (!podName.equals(newLeaderId)) {
                    log.info("Observed new leader {} different from {} - clearing local leader label if it exists",
                             newLeaderId,
                             podName);
                    this.leader = false;
                    upsertLabel();
                }
            } catch (final Exception e) {
                log.error("Error handling new leader notification: {}", e.getMessage(), e);
            }
        };

        // 6) Run leader election in the foreground (blocks)
        //    If you prefer non-blocking, spawn on an executor.
        // 6) Run leader election in the foreground (blocks)
        while (!Thread
                .currentThread()
                .isInterrupted()) {
            log.info("Attempting to acquire leadership on node: {}", podName);
            try (final LeaderElector elector = new LeaderElector(config)) {
                // blocks until leadership lost or elector stops
                elector.run(onStartLeading, onStopLeading, newLeaderConsumer);
            } catch (final RuntimeException e) {
                log.error("Leader election error: {}", e.getMessage(), e);
            }

            // Backoff with jitter after elector exits (normal or error) to allow failover
            final long sleepMs = ThreadLocalRandom
                    .current()
                    .nextLong(MIN_RETRY_MS, MAX_RETRY_MS + 1);
            log.info("LeaderElector exited; sleeping {}ms before next attempt to allow failover", sleepMs);
            try {
                Thread.sleep(sleepMs);
            } catch (final InterruptedException ie) {
                Thread
                        .currentThread()
                        .interrupt();
                log.info("Interrupted while sleeping after elector exit; stopping leadership loop");
                break;
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
