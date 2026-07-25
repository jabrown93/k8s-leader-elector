/**
 * Leader election and the pod labeling that follows from it.
 *
 * <p>{@link io.jaredbrown.k8s.leader.elector.ElectorService} owns the state machine — acquire,
 * renew, relinquish — and is the only class that touches the Redis lock. It delegates every
 * Kubernetes side effect to {@link io.jaredbrown.k8s.leader.elector.LockCallbacks} and every
 * fitness decision to {@link io.jaredbrown.k8s.leader.elector.HealthProbe}, so neither of those
 * knows anything about lock timing or scheduling.
 *
 * <p>Two invariants shape the code here and are easy to break by accident:
 *
 * <ul>
 *   <li>Every lock operation runs on the single scheduler thread, because
 *       {@code DistributedLock.unlock()} is thread-owned. Nothing in this package may move a lock
 *       call onto another thread or block that thread unboundedly.</li>
 *   <li>Labeling is reconciled, not merely reacted to: it runs on acquisition and on every renewal,
 *       and never throws. A labeling failure is a side effect of leadership, not a reason to give it
 *       up, so it is logged and retried on the next tick.</li>
 * </ul>
 *
 * <p>See {@code docs/codebase/ARCHITECTURE.md} for the reconcile pagination/ownership contract and
 * the health-probe threat model.
 */
package io.jaredbrown.k8s.leader.elector;
