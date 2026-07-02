# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kubernetes leader election sidecar application that:

- Acquires distributed leadership using Redis-based locks (via Spring Integration)
- Labels the leader Pod with a configurable label (default: `dns.jb.io/leader=true`)
- Manages label state on leadership changes across all Pods in the StatefulSet/Deployment,
  re-reconciling it on every lock renewal so a missed or late-created pod self-heals

**Status**: Under active development, bugs are likely.

## Build & Test Commands

### Building

```bash
# Clean build with Maven
mvn clean install

# Build only (skip tests if any exist)
mvn clean package
```

### Docker

```bash
# Build multi-platform Docker image (no push)
make docker-build

# Build and push to registry
make docker-release

# Manual build
make build

# Local test build
docker build -t k8s-leader-elector:test .
```

**Container Configuration:**
- Uses `tini` as init system for proper signal handling and zombie process reaping
- JVM configured with `-XX:+UseContainerSupport` to detect container memory/CPU limits
- Heap sized dynamically: 50-75% of container memory allocation
- OOM behavior: exits cleanly and creates heap dump at `/tmp/heapdump.hprof`

### Running Locally

The application requires:

- Redis instance running (configured via `spring.data.redis.host`)
- Kubernetes cluster access (uses default kubeconfig/in-cluster config)
- Environment variable `POD_NAME` set to identify the current pod

## Architecture

### Core Components

**ElectorService** (`src/main/java/io/jaredbrown/k8s/leader/elector/ElectorService.java`)

- Implements Spring `SmartLifecycle` to manage distributed lock lifecycle
- Uses `RedisLockRegistry` from Spring Integration for distributed locking
- On `start()`, labels self `leader=false` (`callbacks.ensureSelfLabeled()`) before the first
  acquisition attempt, so a freshly (re)created pod never sits unlabeled
- Lock acquisition loop with configurable retry period
- Automatic lock renewal via scheduled executor at `renewDeadline` interval; a failed renew is
  retried once immediately before the lock is treated as lost (absorbs a single transient Redis
  blip). A successful renew also re-runs `callbacks.reconcileLeaderLabels()`
- Handles lock loss and attempts reacquisition automatically
- Optional health gating (when `healthProbeEnabled`): an unhealthy pod won't acquire the lock
  (eligibility gate in `lockLoop`), and a leader that goes unhealthy relinquishes after
  `healthProbeFailureThreshold` consecutive failures (liveness gate in `refreshLock`). A
  `healthProbeDeadlockGrace` escape hatch lets a degraded pod lead if no pod is healthy, so the
  system never deadlocks leaderless (e.g. fresh install / total outage).

**HealthProbe** (`src/main/java/io/jaredbrown/k8s/leader/elector/HealthProbe.java`)

- Generic, tool-free fitness check: reads a status file the application maintains (typically on a
  shared `emptyDir`). Healthy iff the file exists, is fresh (within `healthProbeMaxAge`), and its
  trimmed content equals `healthProbeHealthyContent`. Returns `true` when probing is disabled and
  never throws, so callers treat it as a simple boolean gate.

**LockCallbacks** (`src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`)

- Executed by ElectorService on lock state changes and on every successful renewal
- `reconcileLeaderLabels()`: idempotent — lists all pods with the matching selector label and
  patches only those whose label differs from the desired value (leader=true on self, =false on
  everyone else). Never throws; a labeling failure is logged and retried on the next reconcile
  (acquisition, or the next renewal tick at most `renewDeadline` later) rather than costing
  leadership.
- `onLockAcquired()`: delegates to `reconcileLeaderLabels()`
- `ensureSelfLabeled()`: called once on startup so a freshly (re)created pod carries the label from
  boot instead of staying unlabeled until its first election
- `onLockLost()`: removes leader label from self
- Uses Fabric8 Kubernetes client to patch Pod labels

**RedisLockRegistryConfiguration** (
`src/main/java/io/jaredbrown/k8s/leader/configuration/RedisLockRegistryConfiguration.java`)

- Creates `RedisLockRegistry` bean with configurable lease duration
- Lock registry key: `{lockName}-lock-registry`

**ElectorProperties** (`src/main/java/io/jaredbrown/k8s/leader/elector/ElectorProperties.java`)
Configuration properties (prefix: `elector`):

- `labelKey`: Label key to set on leader Pod
- `lockName`: Name of the distributed lock in Redis
- `appName`: Value of `app` label to filter pods for labeling
- `leaseDuration`: Lock TTL in Redis (default: 120s)
- `renewDeadline`: How often to refresh the lock (default: 60s)
- `retryPeriod`: How often to retry lock acquisition when not held (default: 5s)

### Lifecycle Flow

1. **Startup**: ElectorService starts via SmartLifecycle (phase: Integer.MIN_VALUE for early start);
   labels self `leader=false` before the first acquisition attempt
2. **Lock Acquisition**: Attempts to acquire lock from Redis, retries every `retryPeriod`
3. **Leadership**: On acquisition, schedules lock renewal task and calls `onLockAcquired()`
   (reconciles leader labels)
4. **Lock Maintenance**: Renews lock every `renewDeadline` seconds (one immediate retry on a failed
   renew), then re-reconciles leader labels
5. **Lock Loss**: If renewal fails (after its retry) or lock lost, calls `onLockLost()` and
   re-enters acquisition loop
6. **Shutdown**: PreDestroy hook cancels renewal and releases the lock via the scheduler thread

### Key Dependencies

- **Spring Boot 4.0.0**: Core framework
- **Spring Integration Redis**: Distributed lock implementation (`RedisLockRegistry`)
- **Fabric8 Kubernetes Client**: K8s API interactions for Pod labeling
- **Spring Cloud Kubernetes**: Kubernetes-aware configuration
- **Java 25**: Language version (Amazon Corretto in Docker)

## Configuration

### Environment Variables

- `POD_NAME`: Required. Name of the current pod (injected via Kubernetes downward API)
- Redis connection: Via Spring Boot properties or environment variables

### Application Properties

See `src/main/resources/application.properties`:

- `spring.data.redis.host`: Redis server host (default: localhost)
- `server.shutdown=graceful`: Ensures clean lock release on shutdown
- Elector properties: Via `elector.*` prefix

## Important Implementation Notes

### Lock Renewal vs Lease Duration

- `leaseDuration` (120s): How long the lock lives in Redis without renewal
- `renewDeadline` (60s): How often we renew it
- Renewal happens at half the lease duration to provide buffer

### Pod Labeling Strategy

`reconcileLeaderLabels()` brings every pod's label in line with the current election result, and is
called both on acquisition and on every successful renewal (not a one-shot):

1. Queries all Pods with label `app={elector.appName}`
2. For each pod whose current `{elector.labelKey}` value doesn't already match the desired one:
    - Sets `{elector.labelKey}=true` on the current pod (leader)
    - Sets `{elector.labelKey}=false` on every other pod
3. Pods whose label already matches are skipped (idempotent — safe to call every renewal tick)

Individual pod label update failures are logged but don't prevent updating other pods, and never
throw: a labeling problem is a side effect of leadership, not a reason to give it up. A pod list
query failure is likewise logged and retried on the next reconcile rather than escalated. This
means a slow API server, or a pod created after the last election, self-heals within one
`renewDeadline` instead of staying wrong until the next leadership change.

Before mutating each drifted pod, `reconcileLeaderLabels()` re-confirms leadership via a
`stillLeader` callback (`ElectorService#stillOwnsLock`, which calls `RedisLockRegistry.renewLock` —
a Redis-side ownership check whose Lua script only succeeds while Redis still maps the key to this
registry's client id, and which refreshes the lease as a side effect). This closes a TOCTOU: a
reconcile that outlives the lease (very slow API, many drifted pods) must not keep stamping this
pod's stale identity after another pod has already taken over the lock — that would flip the new
leader's label back to `false` and leave the deployment momentarily leaderless. On a lost/unconfirmed
ownership the reconcile halts immediately; labels then converge on the new leader's next reconcile.
The check only fires for pods that actually need a patch, so a steady-state (all-labels-match)
reconcile issues no extra Redis calls.

### Kubernetes Client Request Bounds

`K8sClientConfiguration` overrides two fabric8 defaults on the `KubernetesClient` bean:
`requestTimeout` (10s → 2.5s) and `requestRetryBackoffLimit` (10 → 3). Every K8s API call runs
inline on `ElectorService`'s single scheduler thread, so an unbounded call would (a) block the
shutdown-time lock release past its 5s `RELEASE_TIMEOUT` window and (b) in the extreme stall lock
renewal past the lease while a label reconcile is mid-flight. Bounding the per-call time keeps a whole
reconcile of a handful of pods comfortably inside both windows. The config starts from
`Config.autoConfigure(null)` (in-cluster service-account token, API server, CA, namespace) and edits
only those two fields, so authentication is untouched.

### Thread Safety

- `ElectorService`'s `TaskScheduler` bean is pinned to a single thread (`setPoolSize(1)` in
  `TaskSchedulerConfiguration`) specifically because `DistributedLock.unlock()` is thread-owned
  (`RedisLockRegistry.RedisLock` wraps a local `ReentrantLock` and throws `IllegalStateException` if
  unlocked from a different thread than acquired it). Pinning to one thread keeps acquisition
  (`lockLoop`) and renewal/release (`refreshLock`) on the same thread by construction.
- Lock state is managed via volatile/atomic fields
- Lock acquisition and renewal happen sequentially, never concurrently

### Graceful Shutdown

- `@PreDestroy` → `stop()` cancels the refresh task, then releases the lock
- Since `stop()` itself runs on whatever thread Spring's context shutdown uses (not the scheduler
  thread), it can't call `unlock()` directly — see Thread Safety above. Instead it submits the
  release onto the scheduler (`taskScheduler.submit(...)`) and blocks on the returned `Future` for
  up to 5 seconds (`ElectorService.RELEASE_TIMEOUT`) so the unlock happens on the correct thread
  before the pod terminates
- `TaskSchedulerConfiguration` sets `acceptTasksAfterContextClose(true)` on the scheduler bean. This
  is required, not cosmetic: `ThreadPoolTaskScheduler` listens for `ContextClosedEvent` and, by
  default, calls `executor.shutdown()` right there — and that event is published (synchronously)
  *before* Spring invokes any `SmartLifecycle#stop()`. Without the flag, `stop()`'s `submit()` call
  above would always throw `TaskRejectedException` and the lock would leak every graceful shutdown —
  exactly the bug this scheduler thread-pinning exists to avoid
- If this pod was leading, `stop()` also clears its own `leader=true` label (`callbacks.onShutdown()`)
  after releasing the lock, so it doesn't sit labeled true for the rest of
  `terminationGracePeriodSeconds`
- Spring Boot graceful shutdown ensures in-flight operations complete
