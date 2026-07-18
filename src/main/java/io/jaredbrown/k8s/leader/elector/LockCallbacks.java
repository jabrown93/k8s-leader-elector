package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Callbacks invoked by {@code ElectorService} to keep pod leader labels in sync with the current
 * election result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockCallbacks {
    // Matches client-go's own default chunk size for paginated list calls. Transparent at this
    // app's realistic scale (single digits to low tens of matching pods per selector - one page
    // covers it, same as an unpaginated list()); it only starts mattering under an attacker-
    // inflated matching-pod count, keeping each individual list call small enough to stay well
    // inside K8sClientConfiguration's 2s request timeout regardless of total matching pod count.
    private static final long RECONCILE_LIST_PAGE_SIZE = 500;

    @Nonnull
    private final ElectorProperties electorProperties;
    @Nonnull
    private final KubernetesClient kubernetesClient;

    // No default: POD_NAME identifies this pod for every label decision below, so a missing value
    // must fail context startup rather than silently compare every real pod name against a
    // placeholder that never matches, which would mislabel this pod as a follower even while it
    // holds the Redis lock.
    @Value("${POD_NAME}")
    private String selfPodName;

    /**
     * Fails startup if {@code POD_NAME} is blank.
     *
     * <p>{@code @Value} above only rejects a totally absent property; {@code POD_NAME=""} resolves
     * successfully and would silently reproduce the same bug removing the "unknown" default was
     * meant to fix - every {@code pod.getMetadata().getName().equals(selfPodName)} comparison below
     * would fail, so this pod would never match "isLeader" even while holding the Redis lock.
     */
    @PostConstruct
    void validateSelfPodName() {
        if (!StringUtils.hasText(selfPodName)) {
            throw new IllegalStateException("POD_NAME must not be blank");
        }
    }

    /**
     * Reconciles leader labels after acquiring the lock.
     *
     * @param stillLeader re-confirms leadership mid-reconcile; see {@link #reconcileLeaderLabels}
     */
    public void onLockAcquired(final BooleanSupplier stillLeader) {
        log.info("Lock acquired - reconciling leader labels across deployment");
        reconcileLeaderLabels(stillLeader);
    }

    /**
     * Brings every pod's leader label in line with the current election result: true on self,
     * false on everyone else. Idempotent (skips pods whose label already matches) and safe to call
     * repeatedly, so {@code ElectorService} also calls this on every successful lock renewal — that
     * self-heals a label a prior attempt failed to set (a slow API server, a pod created after the
     * last election) instead of leaving it wrong until the next leadership change. Never throws: a
     * labeling problem is a side effect of leadership, not a reason to give it up, and the next
     * renewal tick (at most {@code renewDeadline} away) retries automatically.
     *
     * <p>Paginated ({@link #RECONCILE_LIST_PAGE_SIZE} per page, matching client-go's own default
     * chunk size) and patches each page's drifted pods before fetching the next, rather than
     * buffering the whole match set first. Both are attacker-inflated-pod-count defenses: an
     * unpaginated {@code list()} could exceed {@code K8sClientConfiguration}'s 2s request timeout
     * outright, and buffering every page before patching would leave heap usage unbounded even
     * though each individual request stays small. Processing page-by-page bounds peak memory to
     * one page's worth of pods regardless of total match count.
     *
     * @param stillLeader re-confirms leadership (Redis-side; see {@code
     *                     ElectorService#stillOwnsLock}) immediately before mutating each drifted
     *                     pod, and again before fetching another page. A reconcile that outlives
     *                     the lease — a very slow API server, many drifted pods, or simply many
     *                     pages — must not keep stamping this pod's stale identity (or keep
     *                     paginating at all) after another pod has taken over the lock: stamping
     *                     stale labels would flip the new leader's label back to false and leave
     *                     the deployment momentarily leaderless, and unconditionally continued
     *                     pagination could stall the single scheduler thread past
     *                     {@code renewDeadline}. Every {@code stillLeader} call besides the last
     *                     also renews the Redis lease as a side effect, so a long-but-still-
     *                     legitimate multi-page reconcile keeps the lease alive instead of racing
     *                     it. The pre-next-page check only fires when another page remains, so the
     *                     common single-page case issues no extra Redis call beyond what patching
     *                     already needs.
     */
    public void reconcileLeaderLabels(final BooleanSupplier stillLeader) {
        final String namespace = kubernetesClient.getNamespace();
        try {
            int updated = 0;
            int failures = 0;
            int total = 0;
            String continueToken = null;
            do {
                final PodList page = kubernetesClient
                        .pods()
                        .inNamespace(namespace)
                        .withLabel(electorProperties.getSelectorLabelKey(), electorProperties.getSelectorLabelValue())
                        .list(new ListOptionsBuilder()
                                      .withLimit(RECONCILE_LIST_PAGE_SIZE)
                                      .withContinue(continueToken)
                                      .build());

                for (final Pod pod : page.getItems()) {
                    total++;
                    final String podName = pod
                            .getMetadata()
                            .getName();
                    final boolean isLeader = podName.equals(selfPodName);

                    if (!needsLabelUpdate(pod, isLeader)) {
                        continue;
                    }
                    if (!stillLeader.getAsBoolean()) {
                        log.warn("Halting leader-label reconcile: leadership no longer confirmed " +
                                 "(was leaderPod={}, {} pods updated before ownership was lost)",
                                 selfPodName,
                                 updated);
                        return;
                    }
                    if (updatePodLeaderLabel(namespace, podName, isLeader)) {
                        updated++;
                    } else {
                        failures++;
                    }
                }

                continueToken = page
                        .getMetadata()
                        .getContinue();
                if (StringUtils.hasText(continueToken) && !stillLeader.getAsBoolean()) {
                    log.warn("Halting leader-label reconcile: leadership no longer confirmed before fetching " +
                             "next page ({} pods updated so far)", updated);
                    return;
                }
            } while (StringUtils.hasText(continueToken));

            if (updated > 0 || failures > 0) {
                log.info("Reconciled leader labels: {} updated, {} failed ({} pods total, leaderPod={})",
                         updated,
                         failures,
                         total,
                         selfPodName);
            }
        } catch (final KubernetesClientException e) {
            log.error("Failed to list pods while reconciling leader labels; will retry on next reconcile", e);
        }
    }

    /**
     * @return whether {@code pod}'s current leader label differs from what {@code isLeader}
     * implies (including when the pod carries no labels map at all)
     */
    private boolean needsLabelUpdate(final Pod pod, final boolean isLeader) {
        final Map<String, String> labels = pod
                .getMetadata()
                .getLabels();
        final String current = labels == null ? null : labels.get(electorProperties.getLabelKey());
        return !Boolean
                .toString(isLeader)
                .equals(current);
    }

    /** Patches {@code podName}'s leader label; propagates any {@link KubernetesClientException}. */
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

    /**
     * @return {@code true} if the patch succeeded; on failure, logs (at error for the leader, warn
     * otherwise) and returns {@code false} rather than throwing
     */
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

    /**
     * Called once on startup so a freshly (re)created pod always carries the label from boot
     * instead of staying unlabeled until it wins (or loses) its first election.
     */
    public void ensureSelfLabeled() {
        log.info("Initializing leader label on self at startup");
        updatePodLeaderLabel(kubernetesClient.getNamespace(), selfPodName, false);
    }

    /** Removes the leader label from self after losing the lock. */
    public void onLockLost() {
        log.warn("Lock lost - removing leader label from self");
        updatePodLeaderLabel(kubernetesClient.getNamespace(), selfPodName, false);
    }

    /**
     * Called on graceful shutdown, but only for a pod that was actually leading (see {@code
     * ElectorService#releaseLockAndClearLabelIfHeld}) — otherwise the label is already false.
     * Without this, a departing leader would stay labeled true for the rest of its
     * {@code terminationGracePeriod}, which anything selecting directly on the label (not just the
     * Service, which drops NotReady endpoints immediately) could still match.
     */
    public void onShutdown() {
        log.info("Shutting down while leading - removing leader label from self");
        updatePodLeaderLabel(kubernetesClient.getNamespace(), selfPodName, false);
    }
}
