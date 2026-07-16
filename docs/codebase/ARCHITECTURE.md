# Architecture

## Core Sections (Required)

### 1) Architectural Style

- Primary style: single-purpose **state-machine sidecar** with a lifecycle-managed background loop, not a request/response service (no controllers, no HTTP API of its own beyond Spring Actuator's `health`/`info`).
- Why this classification: the whole app is one `SmartLifecycle` bean (`ElectorService`) driving a self-scheduling loop (`lockLoop` → `becomeLeader`/`scheduleRetry` → `refreshLock` → `handleLockLost` → back to `lockLoop`) on a dedicated single-thread scheduler (`ElectorService.java:57-389`, `TaskSchedulerConfiguration.java`).
- Primary constraints: (1) all Redis lock operations must run on the same thread because `RedisLockRegistry.RedisLock.unlock()` is thread-owned (`TaskSchedulerConfiguration.java:16-19`); (2) Kubernetes API calls must be time-bounded so they never block that single thread past the lease/shutdown windows (`K8sClientConfiguration.java:12-20`); (3) every operation that can fail (label patch, lock renew, pod list) must be non-throwing/self-healing rather than escalate, because escalation would cost leadership as a side effect of an unrelated failure (documented throughout `LockCallbacks.java` and `ElectorService.java`).

### 2) System Flow

```text
Application.main() -> Spring context startup -> ElectorService.start() (SmartLifecycle, phase=MIN_VALUE)
  -> callbacks.ensureSelfLabeled() (label self leader=false)
  -> lockLoop() [scheduler thread]
       -> healthProbe.isHealthy() + lockRegistry.obtain(...).tryLock(retryPeriod)
       -> acquired & healthy  -> becomeLeader() -> callbacks.onLockAcquired() (reconcileLeaderLabels) -> scheduleRefreshTask()
       -> not acquired        -> scheduleRetry() / scheduleUnhealthyRetry() -> lockLoop() again
  -> refreshLock() [fixed-rate, every renewDeadline, scheduler thread]
       -> health check (relinquish after healthProbeFailureThreshold failures)
       -> renewLockWithRetry() (one immediate retry on failure) -> lockRegistry.renewLock(...)
       -> callbacks.reconcileLeaderLabels(stillOwnsLock) -> LockCallbacks patches Pod labels via KubernetesClient
  -> on failure/loss -> handleLockLost() -> callbacks.onLockLost() (label self false) -> re-enter lockLoop()
  -> shutdown: @PreDestroy -> stop() -> cancelRefreshTask() -> awaitLockRelease() (submitted onto scheduler thread) -> releaseLockAndClearLabelIfHeld()
```

### 3) Layer/Module Responsibilities

| Layer or module | Owns | Must not own | Evidence |
|-----------------|------|--------------|----------|
| `ElectorService` | Lock lifecycle state machine, scheduling/backoff, health-gate eligibility/liveness decisions, single-thread invariant | Kubernetes API details, label semantics | `ElectorService.java` |
| `LockCallbacks` | Pod discovery + label patch/reconcile, `POD_NAME` self-identity, startup/shutdown label hygiene | Redis/lock timing, retry scheduling | `LockCallbacks.java` |
| `HealthProbe` | Reading/interpreting a filesystem status file into a boolean | Any notion of *why* the app is healthy — that's the host application's job | `HealthProbe.java:14-28` |
| `ElectorProperties` | All `elector.*` config binding + validation constraints (`@NotBlank`, `@DurationMin`, `@Min`) | Defaults that silently mask misconfiguration (label/lock/selector keys have no defaults, deliberately) | `ElectorProperties.java` |
| `configuration/*` (`K8sClientConfiguration`, `RedisLockRegistryConfiguration`, `TaskSchedulerConfiguration`) | Bean construction and infra-level tuning (request timeouts, thread pool size, clock) | Business/domain logic | `configuration/*.java` |

### 4) Reused Patterns

| Pattern | Where found | Why it exists |
|---------|-------------|---------------|
| Single-threaded executor confinement | `TaskSchedulerConfiguration.java:20` (`setPoolSize(1)`) | `DistributedLock.unlock()` throws if called from a thread other than the one that locked it; pinning to one thread makes this true by construction rather than by discipline |
| Reconcile-not-just-react (idempotent convergence loop) | `LockCallbacks.reconcileLeaderLabels` called on both acquisition and every renewal (`ElectorService.java:321`) | Self-heals a missed/late pod or a failed patch within one `renewDeadline` instead of waiting for the next leadership change |
| TOCTOU guard via re-confirmation callback | `stillOwnsLock()` passed into `reconcileLeaderLabels` (`ElectorService.java:357-370`, `LockCallbacks.java:87-91`) | A long reconcile could outlive the Redis lease; re-checking ownership before each drifted-pod patch stops a stale leader from overwriting the real new leader's label |
| Fail-open / never-throw callbacks | `LockCallbacks` methods catch `KubernetesClientException` and log rather than propagate (`LockCallbacks.java:106-108,140-149`) | A labeling failure is a side effect of leadership, not a reason to lose it |
| Deadlock escape hatch with grace timer | `ElectorService.deadlockGraceExceeded()` (`ElectorService.java:246-253`) | Prevents permanent leaderless state when no pod is healthy (fresh install / total outage) |
| Cross-thread submit + bounded wait for shutdown | `awaitLockRelease()` (`ElectorService.java:101-114`) | `SmartLifecycle#stop()` runs on Spring's shutdown thread, not the scheduler thread that owns the lock |

### 5) Known Architectural Risks

- Single point of coordination is Redis: the README explicitly documents that anything reachable to the same Redis instance can forge/steal leadership by issuing a raw `SET` on the lock key, since the CAS guarantee only holds against clients speaking the same protocol (`README.md:36-44`). This is a design-level trust boundary, not a bug.
- Single-scheduler-thread design (correct for the lock-ownership constraint) means a slow Kubernetes API call or Redis call inline-blocks the entire lock lifecycle for that duration; mitigated by the 2s/1-retry K8s client bound (`K8sClientConfiguration.java`) but there is no equivalent explicit timeout override for Redis calls beyond Spring Data Redis defaults — see `[ASK USER]` in CONCERNS.md.
- `getPhase()` returns `Integer.MIN_VALUE` for earliest possible `SmartLifecycle` start (`ElectorService.java:385-388`); if a future bean needs to start even earlier (e.g. another `SmartLifecycle` at the same phase with an ordering dependency on this one), Spring does not guarantee ordering within the same phase value.

### 6) Evidence

- `src/main/java/io/jaredbrown/k8s/leader/elector/ElectorService.java`
- `src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`
- `src/main/java/io/jaredbrown/k8s/leader/configuration/TaskSchedulerConfiguration.java`
- `README.md` (Securing Redis, health-gating sections)

## Extended Sections (Optional)

### Startup/Shutdown Ordering Detail

1. Spring context refresh → `@ConfigurationPropertiesScan` binds and validates `ElectorProperties` (startup fails fast on missing `elector.labelKey`/`lockName`/`selectorLabelKey`/`selectorLabelValue` or invalid durations — `ElectorProperties.java:17-39`).
2. `LockCallbacks.validateSelfPodName()` (`@PostConstruct`) fails startup if `POD_NAME` is blank (`LockCallbacks.java:41-46`).
3. `ElectorService.start()` runs at `SmartLifecycle` phase `Integer.MIN_VALUE` — earliest possible — so leadership begins acquiring before other application beans start.
4. Shutdown: `@PreDestroy` on `ElectorService` calls `stop()`, which must complete lock release within `RELEASE_TIMEOUT` (5s) — see CONCERNS.md for the interaction with `terminationGracePeriodSeconds`.
