package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockCallbacksTest {

    @Mock
    private ElectorProperties electorProperties;

    @Mock
    private KubernetesClient kubernetesClient;

    @Mock
    private MixedOperation<Pod, PodList, PodResource> podsOperation;

    @Mock
    private MixedOperation<Pod, PodList, PodResource> namespacedPods;

    @Mock
    private FilterWatchListDeletable<Pod, PodList, PodResource> labeledPods;

    @Mock
    private PodList podList;

    private LockCallbacks lockCallbacks;

    private static final String NAMESPACE = "test-namespace";
    private static final String SELF_POD_NAME = "pod-1";
    private static final String OTHER_POD_NAME = "pod-2";
    private static final String LABEL_KEY = "leader";
    private static final String APP_NAME = "test-app";

    @BeforeEach
    void setUp() {
        lockCallbacks = new LockCallbacks(electorProperties, kubernetesClient);
        ReflectionTestUtils.setField(lockCallbacks, "selfPodName", SELF_POD_NAME);

        lenient().when(electorProperties.getLabelKey()).thenReturn(LABEL_KEY);
        lenient().when(electorProperties.getAppName()).thenReturn(APP_NAME);
        lenient().when(kubernetesClient.getNamespace()).thenReturn(NAMESPACE);
        lenient().when(kubernetesClient.pods()).thenReturn(podsOperation);
        lenient().when(podsOperation.inNamespace(NAMESPACE)).thenReturn(namespacedPods);
    }

    @Test
    void onLockAcquired_shouldQueryPodsWithCorrectLabel() {
        // Given
        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of());

        // When
        lockCallbacks.onLockAcquired();

        // Then
        verify(namespacedPods).withLabel("app", APP_NAME);
        verify(labeledPods).list();
    }

    @Test
    void onLockAcquired_shouldThrowWhenPodListQueryFails() {
        // Given
        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenThrow(new KubernetesClientException("Failed to list pods"));

        // When/Then
        final IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> lockCallbacks.onLockAcquired()
        );

        assertTrue(exception.getMessage().contains("Failed to update leader labels"));
        assertTrue(exception.getCause() instanceof KubernetesClientException);
    }

    @Test
    void onLockLost_shouldInvokeKubernetesClient() {
        // Given - mock the full chain needed for onLockLost
        final PodResource podResource = mock(PodResource.class);
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(podResource);

        // When/Then - should not throw
        lockCallbacks.onLockLost();

        verify(kubernetesClient).getNamespace();
        verify(kubernetesClient).pods();
    }

    private Pod createPod(final String name) {
        final Pod pod = new Pod();
        final ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace(NAMESPACE);

        final Map<String, String> labels = new HashMap<>();
        labels.put("app", APP_NAME);
        metadata.setLabels(labels);

        pod.setMetadata(metadata);
        return pod;
    }
}
