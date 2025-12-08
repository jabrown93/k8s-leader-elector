# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kubernetes leader election sidecar application that:

- Acquires distributed leadership using Redis-based locks (via Spring Integration)
- Labels the leader Pod with a configurable label (default: `dns.jb.io/leader=true`)
- Manages label state on leadership changes across all Pods in the StatefulSet/Deployment

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
```

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
- Lock acquisition loop with configurable retry period
- Automatic lock renewal via scheduled executor at `renewDeadline` interval
- Handles lock loss and attempts reacquisition automatically

**LockCallbacks** (`src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`)

- Executed by ElectorService on lock state changes
- `onLockAcquired()`: Labels all pods with matching `app` label, setting leader=true on self
- `onLockLost()`: Removes leader label from self
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

1. **Startup**: ElectorService starts via SmartLifecycle (phase: Integer.MIN_VALUE for early start)
2. **Lock Acquisition**: Attempts to acquire lock from Redis, retries every `retryPeriod`
3. **Leadership**: On acquisition, schedules lock renewal task and calls `onLockAcquired()`
4. **Lock Maintenance**: Renews lock every `renewDeadline` seconds
5. **Lock Loss**: If renewal fails or lock lost, calls `onLockLost()` and re-enters acquisition loop
6. **Shutdown**: PreDestroy hook releases lock gracefully

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

On lock acquisition, the service ensures exactly one pod has the leader label:

1. Queries all Pods with label `app={elector.appName}`
2. Updates all pods atomically:
   - Sets `{elector.labelKey}=true` on the current pod (new leader)
   - Sets `{elector.labelKey}=false` on all other pods (former leaders)

This two-phase approach ensures only one pod has the leader label at any given time.
Individual pod label update failures are logged but don't prevent updating other pods.
The entire operation fails only if the initial pod list query fails.

### Thread Safety

- `ElectorService` uses a single-threaded ScheduledExecutorService
- Lock state is managed via volatile fields
- Lock acquisition and renewal happen sequentially, never concurrently

### Graceful Shutdown

- `@PreDestroy` annotation ensures lock release on pod termination
- Scheduler waits up to 5 seconds for clean shutdown
- Spring Boot graceful shutdown ensures in-flight operations complete
