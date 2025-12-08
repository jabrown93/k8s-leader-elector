package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        log.info("Lock acquired - marking self as leader");
        updateSelfLeaderLabel(true);
    }

    private void updateSelfLeaderLabel(final boolean isLeader) {
        final String namespace = kubernetesClient.getNamespace();
        try {
            kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withName(selfPodName)
                    .edit(pod -> {
                        final Map<String, String> labels = pod
                                .getMetadata()
                                .getLabels();
                        labels.put(electorProperties.getLabelKey(), Boolean.toString(isLeader));
                        return pod;
                    });
            log.info("Set {}={} on pod {}", electorProperties.getLabelKey(), isLeader, selfPodName);
        } catch (final KubernetesClientException e) {
            final String message = String.format("Failed to update leader label on pod %s", selfPodName);
            log.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    public void onLockLost() {
        log.warn("Lock lost - removing leader label from self");
        updateSelfLeaderLabel(false);
    }
}
