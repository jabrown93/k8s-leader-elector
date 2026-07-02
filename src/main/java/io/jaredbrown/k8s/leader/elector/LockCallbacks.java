package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockCallbacks {
    @Nonnull
    private final ElectorProperties electorProperties;
    @Nonnull
    private final KubernetesClient kubernetesClient;

    @Value("${POD_NAME:unknown}")
    private String selfPodName;

    public void onLockAcquired() {
        log.info("Lock acquired - reconciling leader labels across deployment");
        reconcileLeaderLabels();
    }

    // Brings every pod's leader label in line with the current election result: true on self,
    // false on everyone else. Idempotent (skips pods whose label already matches) and safe to call
    // repeatedly, so ElectorService also calls this on every successful lock renewal — that self-
    // heals a label a prior attempt failed to set (a slow API server, a pod created after the last
    // election) instead of leaving it wrong until the next leadership change. Never throws: a
    // labeling problem is a side effect of leadership, not a reason to give it up, and the next
    // renewal tick (at most renewDeadline away) retries automatically.
    public void reconcileLeaderLabels() {
        final String namespace = kubernetesClient.getNamespace();
        try {
            final List<Pod> pods = kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withLabel(electorProperties.getSelectorLabelKey(), electorProperties.getSelectorLabelValue())
                    .list()
                    .getItems();

            int updated = 0;
            int failures = 0;
            for (final Pod pod : pods) {
                final String podName = pod
                        .getMetadata()
                        .getName();
                final boolean isLeader = podName.equals(selfPodName);

                if (!needsLabelUpdate(pod, isLeader)) {
                    continue;
                }
                if (updatePodLeaderLabel(namespace, podName, isLeader)) {
                    updated++;
                } else {
                    failures++;
                }
            }

            if (updated > 0 || failures > 0) {
                log.info("Reconciled leader labels: {} updated, {} failed ({} pods total, leader={})",
                         updated,
                         failures,
                         pods.size(),
                         selfPodName);
            }
        } catch (final KubernetesClientException e) {
            log.error("Failed to list pods while reconciling leader labels; will retry on next reconcile", e);
        }
    }

    private boolean needsLabelUpdate(final Pod pod, final boolean isLeader) {
        final Map<String, String> labels = pod
                .getMetadata()
                .getLabels();
        final String current = labels == null ? null : labels.get(electorProperties.getLabelKey());
        return !Boolean
                .toString(isLeader)
                .equals(current);
    }

    private void patchPodLeaderLabel(final String namespace, final String podName, final boolean isLeader) {
        final Pod patch = new PodBuilder()
                .withNewMetadata()
                .addToLabels(electorProperties.getLabelKey(), Boolean.toString(isLeader))
                .endMetadata()
                .build();

        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName(podName)
                .patch(PatchContext.of(PatchType.JSON_MERGE), patch);
        log.debug("Set {}={} on pod {}", electorProperties.getLabelKey(), isLeader, podName);
    }

    private boolean updatePodLeaderLabel(final String namespace, final String podName, final boolean isLeader) {
        try {
            patchPodLeaderLabel(namespace, podName, isLeader);
            return true;
        } catch (final KubernetesClientException e) {
            if (isLeader) {
                log.error("Failed to update leader label on elected pod {}; will retry on next reconcile",
                          podName,
                          e);
            } else {
                log.warn("Failed to update leader label on pod {}", podName, e);
            }
            return false;
        }
    }

    // Called once on startup so a freshly (re)created pod always carries the label from boot
    // instead of staying unlabeled until it wins (or loses) its first election.
    public void ensureSelfLabeled() {
        log.info("Initializing leader label on self at startup");
        updatePodLeaderLabel(kubernetesClient.getNamespace(), selfPodName, false);
    }

    public void onLockLost() {
        log.warn("Lock lost - removing leader label from self");
        updatePodLeaderLabel(kubernetesClient.getNamespace(), selfPodName, false);
    }
}
