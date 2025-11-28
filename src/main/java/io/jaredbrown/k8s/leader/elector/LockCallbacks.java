package io.jaredbrown.k8s.leader.elector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class LockCallbacks {
    private final ElectorProperties electorProperties;
    private final KubernetesClient kubernetesClient;
    @Value("${POD_NAME:unknown}")
    private String selfPodName;

    private void updateLeaderLabel(final String namespace, final String podName, final boolean isLeader) {
        try {
            kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .edit(pod -> {
                        final var metadata = pod.getMetadata();
                        Map<String, String> labels = metadata.getLabels();
                        if (labels == null) {
                            labels = new HashMap<>();
                        }
                        labels.put(electorProperties.getLabelKey(), Boolean.toString(isLeader));
                        metadata.setLabels(labels);
                        pod.setMetadata(metadata);
                        return pod;
                    });
            log.info("Set {}={} on pod {}", electorProperties.getLabelKey(), isLeader, podName);
        } catch (final Exception e) {
            log.error("Failed to update leader label on pod {}", podName, e);
        }
    }

    public void onLockAcquired() {
        log.info("### Lock acquired – starting protected work");

        final String namespace = kubernetesClient.getNamespace();

        try {
            final List<Pod> podList = kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withLabel("app", electorProperties.getAppName())
                    .list()
                    .getItems();

            for (final Pod pod : podList) {
                final String podName = pod
                        .getMetadata()
                        .getName();
                final boolean isLeader = podName.equals(selfPodName);
                updateLeaderLabel(namespace, podName, isLeader);
            }
        } catch (final Exception e) {
            log.error("Failed to update leader labels on lock acquisition", e);
        }

        // e.g. start scheduled job, enable message processing, etc.
    }

    public void onLockLost() {
        log.warn("### Lock lost – stopping protected work");
        final String namespace = kubernetesClient.getNamespace();
        updateLeaderLabel(namespace, selfPodName, false);
    }
}
