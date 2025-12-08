package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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
    private final ElectorProperties electorProperties;
    private final KubernetesClient kubernetesClient;

    @Value("${POD_NAME:unknown}")
    private String selfPodName;

    public void onLockAcquired() {
        log.info("Lock acquired - updating leader labels across deployment");
        String namespace = kubernetesClient.getNamespace();

        try {
            // Get all pods in the deployment
            List<Pod> pods = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withLabel("app", electorProperties.getAppName())
                .list()
                .getItems();

            // Update all pods: set leader=true on self, leader=false on others
            for (Pod pod : pods) {
                String podName = pod.getMetadata().getName();
                boolean isLeader = podName.equals(selfPodName);
                updatePodLeaderLabel(namespace, podName, isLeader);
            }

            log.info("Successfully updated leader labels: {} is leader, {} other pods marked as non-leader",
                selfPodName, pods.size() - 1);
        } catch (KubernetesClientException e) {
            String message = "Failed to update leader labels on lock acquisition";
            log.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    private void updatePodLeaderLabel(String namespace, String podName, boolean isLeader) {
        try {
            kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName(podName)
                .edit(pod -> {
                    Map<String, String> labels = pod.getMetadata().getLabels();
                    labels.put(electorProperties.getLabelKey(), Boolean.toString(isLeader));
                    return pod;
                });
            log.debug("Set {}={} on pod {}", electorProperties.getLabelKey(), isLeader, podName);
        } catch (KubernetesClientException e) {
            log.warn("Failed to update leader label on pod {}: {}", podName, e.getMessage());
            // Don't throw - we want to continue updating other pods
        }
    }

    public void onLockLost() {
        log.warn("Lock lost - removing leader label from self");
        String namespace = kubernetesClient.getNamespace();
        updatePodLeaderLabel(namespace, selfPodName, false);
    }
}
