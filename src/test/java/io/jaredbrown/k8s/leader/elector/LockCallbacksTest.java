package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void validateSelfPodName_shouldRejectBlankValue() {
        ReflectionTestUtils.setField(lockCallbacks, "selfPodName", "   ");

        assertThrows(IllegalStateException.class, () -> lockCallbacks.validateSelfPodName());
    }

    @Test
    void validateSelfPodName_shouldRejectEmptyValue() {
        ReflectionTestUtils.setField(lockCallbacks, "selfPodName", "");

        assertThrows(IllegalStateException.class, () -> lockCallbacks.validateSelfPodName());
    }

    @Test
    void validateSelfPodName_shouldAcceptNonBlankValue() {
        assertDoesNotThrow(() -> lockCallbacks.validateSelfPodName());
    }

    @Test
    void onLockAcquired_shouldPatchPodsWithJsonMergePatch() {
        // Given
        final PodResource leaderPodResource = mock(PodResource.class);
        final PodResource followerPodResource = mock(PodResource.class);
        final Pod leaderPod = pod(SELF_POD_NAME);
        final Pod followerPod = pod("pod-2");

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, followerPod));
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(leaderPodResource);
        when(namespacedPods.withName("pod-2")).thenReturn(followerPodResource);

        // When
        lockCallbacks.onLockAcquired(() -> true);

        // Then
        verify(namespacedPods).withLabel("app", APP_NAME);
        verify(labeledPods).list();

        final ArgumentCaptor<PatchContext> patchContextCaptor = ArgumentCaptor.forClass(PatchContext.class);
        final ArgumentCaptor<Pod> leaderPatchCaptor = ArgumentCaptor.forClass(Pod.class);
        verify(leaderPodResource).patch(patchContextCaptor.capture(), leaderPatchCaptor.capture());
        assertEquals(PatchType.JSON_MERGE, patchContextCaptor
                .getValue()
                .getPatchType());
        assertEquals("true", leaderPatchCaptor
                .getValue()
                .getMetadata()
                .getLabels()
                .get(LABEL_KEY));
        assertNull(leaderPatchCaptor.getValue().getSpec());

        final ArgumentCaptor<Pod> followerPatchCaptor = ArgumentCaptor.forClass(Pod.class);
        verify(followerPodResource).patch(patchContextCaptor.capture(), followerPatchCaptor.capture());
        assertEquals("false", followerPatchCaptor
                .getValue()
                .getMetadata()
                .getLabels()
                .get(LABEL_KEY));
    }

    @Test
    void reconcileLeaderLabels_shouldSkipPodsWhoseLabelAlreadyMatches() {
        // Given: both pods already carry the label value the election result implies.
        final Pod leaderPod = podWithLabel(SELF_POD_NAME, "true");
        final Pod followerPod = podWithLabel("pod-2", "false");

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, followerPod));

        // When
        lockCallbacks.reconcileLeaderLabels(() -> true);

        // Then: idempotent — no pod needed a patch, so none was attempted.
        verify(namespacedPods, never()).withName(any(String.class));
    }

    @Test
    void reconcileLeaderLabels_shouldPatchOnlyPodsThatDrifted() {
        // Given: self already correctly labeled leader=true, peer stuck on a stale true.
        final PodResource peerPodResource = mock(PodResource.class);
        final Pod leaderPod = podWithLabel(SELF_POD_NAME, "true");
        final Pod stalePeerPod = podWithLabel("pod-2", "true");

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, stalePeerPod));
        when(namespacedPods.withName("pod-2")).thenReturn(peerPodResource);

        // When
        lockCallbacks.reconcileLeaderLabels(() -> true);

        // Then
        verify(namespacedPods, never()).withName(SELF_POD_NAME);
        verify(peerPodResource).patch(any(PatchContext.class), any(Pod.class));
    }

    @Test
    void reconcileLeaderLabels_shouldNotPatchAnyPodWhenOwnershipAlreadyLost() {
        // Given: two pods have drifted labels, but this pod has already lost leadership.
        final Pod leaderPod = podWithLabel(SELF_POD_NAME, "false"); // drifted: should be true if leader
        final Pod peerPod = podWithLabel("pod-2", "true");          // drifted: should be false

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, peerPod));

        // When: the ownership recheck fails before the first mutation.
        lockCallbacks.reconcileLeaderLabels(() -> false);

        // Then: not a single pod was patched — no stamping stale labels after another pod took over.
        verify(namespacedPods, never()).withName(any(String.class));
    }

    @Test
    void reconcileLeaderLabels_shouldRecheckOwnershipBeforeEachDriftedPod() {
        // Given: two drifted pods that both need a patch.
        final PodResource leaderPodResource = mock(PodResource.class);
        final PodResource peerPodResource = mock(PodResource.class);
        final Pod leaderPod = podWithLabel(SELF_POD_NAME, "false");
        final Pod peerPod = podWithLabel("pod-2", "true");

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, peerPod));
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(leaderPodResource);
        when(namespacedPods.withName("pod-2")).thenReturn(peerPodResource);

        final AtomicInteger checks = new AtomicInteger();

        // When
        lockCallbacks.reconcileLeaderLabels(() -> {
            checks.incrementAndGet();
            return true;
        });

        // Then: ownership was re-confirmed once per pod actually mutated, and both were patched.
        assertEquals(2, checks.get());
        verify(leaderPodResource).patch(any(PatchContext.class), any(Pod.class));
        verify(peerPodResource).patch(any(PatchContext.class), any(Pod.class));
    }

    @Test
    void reconcileLeaderLabels_shouldStopPatchingOnceOwnershipLostMidReconcile() {
        // Given: first drifted pod is patched while still leader; ownership is lost before the second.
        final PodResource leaderPodResource = mock(PodResource.class);
        final Pod leaderPod = podWithLabel(SELF_POD_NAME, "false");
        final Pod peerPod = podWithLabel("pod-2", "true");

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(leaderPod, peerPod));
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(leaderPodResource);

        final Iterator<Boolean> ownership = List.of(true, false).iterator();

        // When
        lockCallbacks.reconcileLeaderLabels(ownership::next);

        // Then: the first pod was patched, but the reconcile halted before touching the second — it
        // never even resolved pod-2's resource.
        verify(leaderPodResource).patch(any(PatchContext.class), any(Pod.class));
        verify(namespacedPods, never()).withName("pod-2");
    }

    @Test
    void reconcileLeaderLabels_shouldTreatNullLabelsMapAsNeedingUpdate() {
        // Given: a pod whose metadata carries no labels map at all (not merely missing the leader
        // key) — e.g. a pod that predates the label selector requirement.
        final PodResource podResource = mock(PodResource.class);
        final Pod podWithNoLabels = pod("pod-2");
        podWithNoLabels
                .getMetadata()
                .setLabels(null);

        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(podWithNoLabels));
        when(namespacedPods.withName("pod-2")).thenReturn(podResource);

        // When
        lockCallbacks.reconcileLeaderLabels(() -> true);

        // Then: a null labels map is treated as drifted (current=null != "false") and gets patched.
        verify(podResource).patch(any(PatchContext.class), any(Pod.class));
    }

    @Test
    void reconcileLeaderLabels_shouldNotThrowWhenPodListQueryFails() {
        // Given
        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenThrow(new KubernetesClientException("Failed to list pods"));

        // When/Then: a transient API failure must not cost leadership; the next renewal-tick
        // reconcile (ElectorService#refreshLock) retries automatically.
        assertDoesNotThrow(() -> lockCallbacks.reconcileLeaderLabels(() -> true));
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

    @Test
    void reconcileLeaderLabels_shouldNotThrowWhenLeaderPodLabelUpdateFails() {
        // Given
        final PodResource leaderPodResource = mock(PodResource.class);
        when(namespacedPods.withLabel("app", APP_NAME)).thenReturn(labeledPods);
        when(labeledPods.list()).thenReturn(podList);
        when(podList.getItems()).thenReturn(List.of(pod(SELF_POD_NAME)));
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(leaderPodResource);
        when(leaderPodResource.patch(any(PatchContext.class), any(Pod.class)))
                .thenThrow(new KubernetesClientException("immutable spec update"));

        // When/Then: keep the lock; the next renewal-tick reconcile retries the self-label patch.
        assertDoesNotThrow(() -> lockCallbacks.reconcileLeaderLabels(() -> true));
        verify(leaderPodResource).patch(any(PatchContext.class), any(Pod.class));
    }

    @Test
    void ensureSelfLabeled_shouldSetSelfLabelToFalse() {
        // Given
        final PodResource selfPodResource = mock(PodResource.class);
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(selfPodResource);

        // When
        lockCallbacks.ensureSelfLabeled();

        // Then: every pod carries the label from boot, before it has contested any election.
        final ArgumentCaptor<Pod> patchCaptor = ArgumentCaptor.forClass(Pod.class);
        verify(selfPodResource).patch(any(PatchContext.class), patchCaptor.capture());
        assertEquals("false", patchCaptor
                .getValue()
                .getMetadata()
                .getLabels()
                .get(LABEL_KEY));
    }

    @Test
    void ensureSelfLabeled_shouldNotThrowWhenPatchFails() {
        // Given
        final PodResource selfPodResource = mock(PodResource.class);
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(selfPodResource);
        when(selfPodResource.patch(any(PatchContext.class), any(Pod.class)))
                .thenThrow(new KubernetesClientException("API server unavailable"));

        // When/Then
        assertDoesNotThrow(() -> lockCallbacks.ensureSelfLabeled());
    }

    @Test
    void onShutdown_shouldSetSelfLabelToFalse() {
        // Given
        final PodResource selfPodResource = mock(PodResource.class);
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(selfPodResource);

        // When
        lockCallbacks.onShutdown();

        // Then: a departing leader must not stay labeled true through its termination grace period.
        final ArgumentCaptor<Pod> patchCaptor = ArgumentCaptor.forClass(Pod.class);
        verify(selfPodResource).patch(any(PatchContext.class), patchCaptor.capture());
        assertEquals("false", patchCaptor
                .getValue()
                .getMetadata()
                .getLabels()
                .get(LABEL_KEY));
    }

    @Test
    void onShutdown_shouldNotThrowWhenPatchFails() {
        // Given
        final PodResource selfPodResource = mock(PodResource.class);
        when(namespacedPods.withName(SELF_POD_NAME)).thenReturn(selfPodResource);
        when(selfPodResource.patch(any(PatchContext.class), any(Pod.class)))
                .thenThrow(new KubernetesClientException("API server unavailable"));

        // When/Then
        assertDoesNotThrow(() -> lockCallbacks.onShutdown());
    }

    private static Pod pod(final String name) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .build();
    }

    private static Pod podWithLabel(final String name, final String labelValue) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(Map.of(LABEL_KEY, labelValue))
                .endMetadata()
                .build();
    }
}
