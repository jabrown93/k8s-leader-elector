package io.jaredbrown.k8s.leader.elector;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LockCallbacksTest {

    private static final String NAMESPACE = "test-namespace";
    private static final String SELF_POD_NAME = "pod-1";
    private static final String LABEL_KEY = "leader";
    private static final String APP_NAME = "test-app";
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

    @BeforeEach
    void setUp() {
        lockCallbacks = new LockCallbacks(electorProperties, kubernetesClient);
        ReflectionTestUtils.setField(lockCallbacks, "selfPodName", SELF_POD_NAME);

        lenient()
                .when(electorProperties.getLabelKey())
                .thenReturn(LABEL_KEY);
        lenient()
                .when(electorProperties.getSelectorLabelValue())
                .thenReturn(APP_NAME);
        lenient()
                .when(electorProperties.getSelectorLabelKey())
                .thenReturn("app");
        lenient()
                .when(kubernetesClient.getNamespace())
                .thenReturn(NAMESPACE);
        lenient()
                .when(kubernetesClient.pods())
                .thenReturn(podsOperation);
        lenient()
                .when(podsOperation.inNamespace(NAMESPACE))
                .thenReturn(namespacedPods);
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
        final IllegalStateException exception = assertThrows(IllegalStateException.class,
                                                             () -> lockCallbacks.onLockAcquired());

        assertTrue(exception
                           .getMessage()
                           .contains("Failed to update leader labels"));
        assertInstanceOf(KubernetesClientException.class, exception.getCause());
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
}
