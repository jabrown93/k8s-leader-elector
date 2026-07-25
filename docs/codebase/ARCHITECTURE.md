# Architecture

## Core Sections (Required)

### 1) Architectural Style

- Primary style: single-purpose **state-machine sidecar** with a lifecycle-managed background loop, not a request/response service (no controllers, no HTTP API of its own beyond Spring Actuator's `health`/`info`).
- Why this classification: the whole app is one `SmartLifecycle` bean (`ElectorService`) driving a self-scheduling loop (`lockLoop` → `becomeLeader`/`scheduleRetry` → `refreshLock` → `handleLockLost` → back to `lockLoop`) on a dedicated single-thread scheduler (`ElectorService.java`, `TaskSchedulerConfiguration.java`).
- Primary constraints: (1) all Redis lock operations must run on the same thread because `RedisLockRegistry.RedisLock.unlock()` is thread-owned (`TaskSchedulerConfiguration.java`); (2) Kubernetes API calls must be time-bounded so they never block that single thread past the lease/shutdown windows (`K8sClientConfiguration.java`); (3) every operation that can fail (label patch, lock renew, pod list) must be non-throwing/self-healing rather than escalate, because escalation would cost leadership as a side effect of an unrelated failure (documented throughout `LockCallbacks.java` and `ElectorService.java`).

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
| `HealthProbe` | Reading/interpreting a filesystem status file into a boolean | Any notion of *why* the app is healthy — that's the host application's job | `HealthProbe.java` |
| `ElectorProperties` | All `elector.*` config binding + validation constraints (`@NotBlank`, `@DurationMin`, `@Min`) | Defaults that silently mask misconfiguration (label/lock/selector keys have no defaults, deliberately) | `ElectorProperties.java` |
| `configuration/*` (`K8sClientConfiguration`, `RedisLockRegistryConfiguration`, `TaskSchedulerConfiguration`) | Bean construction and infra-level tuning (request timeouts, thread pool size, clock) | Business/domain logic | `configuration/*.java` |

### 4) Reused Patterns

| Pattern | Where found | Why it exists |
|---------|-------------|---------------|
| Single-threaded executor confinement | `TaskSchedulerConfiguration.java` (`setPoolSize(1)`) | `DistributedLock.unlock()` throws if called from a thread other than the one that locked it; pinning to one thread makes this true by construction rather than by discipline |
| Reconcile-not-just-react (idempotent convergence loop) | `LockCallbacks.reconcileLeaderLabels` called on both acquisition and every renewal (`ElectorService#becomeLeader`, `ElectorService#refreshLock`) | Self-heals a missed/late pod or a failed patch within one `renewDeadline` instead of waiting for the next leadership change |
| TOCTOU guard via re-confirmation callback | `stillOwnsLock()` passed into `reconcileLeaderLabels` (`ElectorService.java`, `LockCallbacks.java`) | A long reconcile could outlive the Redis lease; re-checking ownership before each drifted-pod patch stops a stale leader from overwriting the real new leader's label |
| Fail-open / never-throw callbacks | `LockCallbacks` methods catch `KubernetesClientException` and log rather than propagate (`LockCallbacks.java`) | A labeling failure is a side effect of leadership, not a reason to lose it |
| Deadlock escape hatch with grace timer | `ElectorService.deadlockGraceExceeded()` (`ElectorService.java`) | Prevents permanent leaderless state when no pod is healthy (fresh install / total outage) |
| Cross-thread submit + bounded wait for shutdown | `awaitLockRelease()` (`ElectorService.java`) | `SmartLifecycle#stop()` runs on Spring's shutdown thread, not the scheduler thread that owns the lock |

### 5) Known Architectural Risks

- Single point of coordination is Redis: the README explicitly documents that anything reachable to the same Redis instance can forge/steal leadership by issuing a raw `SET` on the lock key, since the CAS guarantee only holds against clients speaking the same protocol (`README.md`, "Securing Redis"). This is a design-level trust boundary, not a bug.
- Single-scheduler-thread design (correct for the lock-ownership constraint) means a slow Kubernetes API call or Redis call inline-blocks the entire lock lifecycle for that duration; mitigated by the 2s/1-retry K8s client bound (`K8sClientConfiguration.java`) but there is no equivalent explicit timeout override for Redis calls beyond Spring Data Redis defaults — see `[ASK USER]` in CONCERNS.md.
- `getPhase()` returns `Integer.MIN_VALUE` for earliest possible `SmartLifecycle` start (`ElectorService.java`); if a future bean needs to start even earlier (e.g. another `SmartLifecycle` at the same phase with an ordering dependency on this one), Spring does not guarantee ordering within the same phase value.

### 6) Evidence

- `src/main/java/io/jaredbrown/k8s/leader/elector/ElectorService.java`
- `src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`
- `src/main/java/io/jaredbrown/k8s/leader/configuration/TaskSchedulerConfiguration.java`
- `README.md` (Securing Redis, health-gating sections)

## Extended Sections (Optional)

### Leader-Label Reconcile: Pagination and Ownership Re-Confirmation

`LockCallbacks.reconcileLeaderLabels(stillLeader)` lists matching pods in pages of
`RECONCILE_LIST_PAGE_SIZE` (500, matching client-go's own default chunk size) and patches each
page's drifted pods before fetching the next, rather than buffering the whole match set. Both
choices bound an attacker-inflated matching-pod count: an unpaginated `list()` could exceed
`K8sClientConfiguration`'s 2s request timeout outright, and buffering every page before patching
would leave heap usage unbounded even though each individual request stays small. Page-by-page
processing bounds peak memory to one page regardless of total match count.

`stillLeader` (wired to `ElectorService#stillOwnsLock`) re-confirms leadership immediately before
mutating each drifted pod, and again before fetching another page:

- A reconcile can outlive the lease — very slow API server, many drifted pods, or simply many
  pages. Stamping labels after another pod has taken over would flip the new leader's label back to
  `false` and leave the deployment momentarily leaderless.
- Unconditionally continued pagination could stall the single scheduler thread past
  `renewDeadline`.
- Every `stillLeader` call except the last also renews the Redis lease as a side effect, so a long
  but still legitimate multi-page reconcile keeps its lease alive instead of racing it.
- The pre-next-page check only fires when another page remains, so the common single-page case
  issues no extra Redis call beyond what patching already needs.

`stillOwnsLock` also gates on `running`: `renewLock` alone would keep succeeding straight through
shutdown, letting a many-page reconcile stall the scheduler thread past `stop()`'s 5s
`RELEASE_TIMEOUT`. This matters most for the acquisition-time reconcile
(`becomeLeader` → `onLockAcquired`), which runs before `scheduleRefreshTask()` creates
`refreshFuture` — `cancelRefreshTask()` has nothing to interrupt yet, so the `running` check is the
only thing that can cut that reconcile short and let the queued lock-release task run.

### Why the Scheduler Accepts Tasks After Context Close

`TaskSchedulerConfiguration` sets `setAcceptTasksAfterContextClose(true)`, which is required rather
than cosmetic. `ExecutorConfigurationSupport` listens for `ContextClosedEvent` and by default calls
`executor.shutdown()` there — and that event is published and handled synchronously *before* Spring
invokes any `SmartLifecycle#stop()` (see `AbstractApplicationContext#doClose`: the
`ContextClosedEvent` publish precedes `lifecycleProcessor.onClose()`). Since `ElectorService.stop()`
submits the shutdown-time lock release to this same scheduler, without the flag that `submit()`
would always throw `TaskRejectedException` and the lock would leak on every graceful shutdown — the
exact failure the thread-pinning exists to prevent. Setting it defers the executor's shutdown to its
later `DisposableBean` callback, which runs after all `SmartLifecycle` beans have stopped.

### Health Status File: Hardening and Read Bounds

`HealthProbe.isHealthy()` checks file type, readability, freshness, and content in separate
filesystem calls, not one atomic read. Consequences:

- **Writer contract.** The application must write a temporary file in the same directory and
  atomically rename it into place (`Files.move(tmp, target, ATOMIC_MOVE)`), never write the target
  in place — otherwise the probe can observe a partially-written file and report a spurious,
  transient unhealthy.
- **File type.** Only a regular file is accepted; a FIFO, socket, device file, directory, or symlink
  is reported unhealthy without being opened, so a symlink planted at the configured path cannot
  redirect the read to any other file the process can access. On a content mismatch only a fixed
  message is logged, never the file's contents, so the probe cannot exfiltrate file data into
  application logs.
- **Read timeout.** Because the type check and the read are separate calls, a co-located writer that
  swaps in a FIFO between them can still route the read into a blocking `open()`. The read therefore
  runs on a single background thread bounded by `readTimeout`, so a blocked open wedges only that
  thread, which is abandoned rather than interrupted (a blocking FIFO open is not interruptible). A
  timed-out read replaces the executor, so a later call — once the path is a normal file again —
  gets a usable thread instead of queuing behind the abandoned task forever.

### Startup/Shutdown Ordering Detail

1. Spring context refresh → `@ConfigurationPropertiesScan` binds and validates `ElectorProperties` (startup fails fast on missing `elector.labelKey`/`lockName`/`selectorLabelKey`/`selectorLabelValue` or invalid durations — `ElectorProperties.java`).
2. `LockCallbacks.validateSelfPodName()` (`@PostConstruct`) fails startup if `POD_NAME` is blank (`LockCallbacks.java`).
3. `ElectorService.start()` runs at `SmartLifecycle` phase `Integer.MIN_VALUE` — earliest possible — so leadership begins acquiring before other application beans start.
4. Shutdown: `@PreDestroy` on `ElectorService` calls `stop()`, which must complete lock release within `RELEASE_TIMEOUT` (5s) — see CONCERNS.md for the interaction with `terminationGracePeriodSeconds`.
