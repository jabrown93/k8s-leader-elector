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
        log.info("Lock acquired - updating leader labels across deployment");
        final String namespace = kubernetesClient.getNamespace();

        try {
            // Get all pods in the deployment
            final List<Pod> pods = kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withLabel(electorProperties.getSelectorLabelKey(), electorProperties.getSelectorLabelValue())
                    .list()
                    .getItems();

            int nonLeaderUpdateFailures = 0;

            // Update all pods: set leader=true on self, leader=false on others
            for (final Pod pod : pods) {
                final String podName = pod
                        .getMetadata()
                        .getName();
                final boolean isLeader = podName.equals(selfPodName);

                if (isLeader) {
                    try {
                        patchPodLeaderLabel(namespace, podName, true);
                    } catch (final KubernetesClientException e) {
                        final String message = "Failed to update leader label on elected pod " + selfPodName;
                        log.error(message, e);
                        throw new IllegalStateException(message, e);
                    }
                } else if (!updateNonLeaderPodLabel(namespace, podName)) {
                    nonLeaderUpdateFailures++;
                }
            }

            final int nonLeaderPods = pods.size() - 1;
            if (nonLeaderUpdateFailures == 0) {
                log.info("Successfully updated leader labels: {} is leader, {} other pods marked as non-leader",
                         selfPodName,
                         nonLeaderPods);
            } else {
                log.warn("Updated leader label on {}, but failed to mark {} of {} other pods as non-leader",
                         selfPodName,
                         nonLeaderUpdateFailures,
                         nonLeaderPods);
            }
        } catch (final KubernetesClientException e) {
            final String message = "Failed to update leader labels on lock acquisition";
            log.error(message, e);
            throw new IllegalStateException(message, e);
        }
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

    private boolean updateNonLeaderPodLabel(final String namespace, final String podName) {
        try {
            patchPodLeaderLabel(namespace, podName, false);
            return true;
        } catch (final KubernetesClientException e) {
            log.warn("Failed to update leader label on pod {}", podName, e);
            return false;
        }
    }

    public void onLockLost() {
        log.warn("Lock lost - removing leader label from self");
        final String namespace = kubernetesClient.getNamespace();
        updateNonLeaderPodLabel(namespace, selfPodName);
    }
}
