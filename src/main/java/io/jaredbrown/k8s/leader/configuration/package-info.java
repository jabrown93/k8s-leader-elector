/**
 * Bean definitions for the infrastructure the elector drives: the Kubernetes client, the Redis lock
 * registry, and the scheduler plus clock.
 *
 * <p>These classes carry no domain logic. What they do carry is tuning the elector depends on for
 * correctness rather than performance — a single-threaded scheduler, bounded Kubernetes request
 * timeouts — so the rationale lives on each bean method and in
 * {@code docs/codebase/ARCHITECTURE.md}. Relaxing any of it changes behavior under failure.
 */
package io.jaredbrown.k8s.leader.configuration;
