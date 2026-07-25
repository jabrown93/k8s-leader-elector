/**
 * Kubernetes leader-election sidecar: acquires a Redis-backed distributed lock and labels the
 * winning pod so other resources can select on leadership.
 *
 * <p>The application has no API of its own; it is a background loop with a lifecycle. Election lives
 * in {@link io.jaredbrown.k8s.leader.elector}, bean wiring in
 * {@link io.jaredbrown.k8s.leader.configuration}.
 */
package io.jaredbrown.k8s.leader;
