# External Integrations

## Core Sections (Required)

### 1) Integration Inventory

| System | Type (API/DB/Queue/etc) | Purpose | Auth model | Criticality | Evidence |
|--------|---------------------------|---------|------------|-------------|----------|
| Redis | Distributed lock backend (via `RedisLockRegistry`) | Source of truth for who holds leadership (CAS lock on a key) | None enabled by default; optional `spring.data.redis.password` / `spring.data.redis.ssl.enabled` supported by Spring Data Redis but not configured in this repo | High — losing Redis reachability halts all lock acquisition/renewal | `RedisLockRegistryConfiguration.java`, `application.properties:3`, `README.md:36-44` |
| Kubernetes API server | REST API (via Fabric8 `KubernetesClient`) | List/patch Pods to set the leader label | In-cluster service-account token / default kubeconfig via `Config.autoConfigure(null)` | High — labeling is the entire externally-visible purpose of the app | `K8sClientConfiguration.java`, `LockCallbacks.java` |
| Pod status file (local filesystem, typically a shared `emptyDir`) | File-based health signal, not a network integration | Lets the *host* application (not this sidecar) report its own fitness to lead | N/A — filesystem read only | Optional (only when `elector.healthProbeEnabled=true`) | `HealthProbe.java`, `README.md:46-72` |
| GHCR (`ghcr.io/jabrown93`) | Container registry | Publishes the built Docker image | GitHub Actions `GITHUB_TOKEN` (release.yml) | Build/release only, not runtime | `.github/workflows/release.yml:48-53`, `Makefile:69` |
| Dependency-Track (in-cluster, homelab) | SBOM ingestion service | Supply-chain visibility on resolved Maven deps | GitHub OIDC → OpenBao-issued API key, in-cluster only | Build/release only, not runtime | `.github/workflows/dt-sbom.yml`, `.github/workflows/pr-license-check.yml` |
| Sigstore/cosign (Fulcio/Rekor) | Keyless image signing | Signs the published image digest for Kyverno verification downstream | GitHub OIDC (keyless) | Build/release only, not runtime | `.github/workflows/release.yml:102-119` |

### 2) Data Stores

| Store | Role | Access layer | Key risk | Evidence |
|-------|------|--------------|----------|----------|
| Redis | Distributed lock state (key: `{lockName}-lock-registry`) | `org.springframework.integration.redis.util.RedisLockRegistry`, wired in `RedisLockRegistryConfiguration` | Any client that can reach this Redis instance and issue a raw `SET` on the lock key can forge/steal leadership — the CAS Lua script only protects against clients using the same protocol correctly, not against a malicious/compromised client on the same network (explicitly called out in README) | `README.md:36-44`, `RedisLockRegistryConfiguration.java` |
| Kubernetes (etcd, indirectly) | Pod label storage | Fabric8 `KubernetesClient` `PATCH` (`JSON_MERGE`) on Pod metadata | Reconcile loop halts on `KubernetesClientException` but retries on the next tick rather than escalating — a persistently unreachable API server means labels silently drift stale until it recovers | `LockCallbacks.java:66-109`, `K8sClientConfiguration.java` |

### 3) Secrets and Credentials Handling

- Credential sources: Kubernetes API auth comes from the in-cluster service-account token via `Config.autoConfigure(null)` (`K8sClientConfiguration.java:26`) — no credential is read/handled directly in application code. Redis credentials, if used, would come from Spring Boot config (`spring.data.redis.password`) — not present in this repo's `application.properties`, so Redis auth is currently disabled by default (documented risk, `README.md:38-44`).
- Hardcoding checks: no hardcoded secrets, tokens, or credentials found in source (`docs/codebase/.codebase-scan.txt` found no `.env` files; no secret-like strings observed while reading all main-source files).
- Rotation or lifecycle notes: `[TODO]` — no rotation policy documented for the CI-side `GH_TOKEN`, `DOCKERHUB_USERNAME`/`DOCKERHUB_PASSWORD`, or the OpenBao-issued Dependency-Track API key; these are GitHub Actions repository secrets, out of scope for this codebase's own docs.

### 4) Reliability and Failure Behavior

- Retry/backoff behavior: extensively implemented and documented in-code. Lock acquisition retries every `elector.retryPeriod` (default 5s); a failed lock renewal gets exactly one immediate retry before being treated as lost (`ElectorService.renewLockWithRetry`, `ElectorService.java:331-340`); an unhealthy pod backs off on the longer `elector.healthProbeUnhealthyBackoff` (default 30s) instead of the tight retry period to avoid starving healthy peers (`ElectorService.java:178-208`).
- Timeout policy: Kubernetes client calls bounded to 2s request timeout / 1 retry (`K8sClientConfiguration.java:19-20`), specifically to keep the single scheduler thread from stalling past the 5s shutdown release window or the lease. No explicit Redis command timeout override found — relies on Spring Data Redis / Lettuce defaults (`[ASK USER]`, see CONCERNS.md).
- Circuit-breaker or fallback behavior: the "deadlock escape hatch" (`ElectorService.deadlockGraceExceeded`) is a domain-specific fallback — after `healthProbeDeadlockGrace` (default 5m) with no healthy candidate, a degraded pod leads anyway rather than leaving the system leaderless forever (`ElectorService.java:159-167,246-253`).

### 5) Observability for Integrations

- Logging around external calls: yes — every Redis and Kubernetes operation is logged at `info`/`warn`/`error` with context (lock name, pod name, counts) via SLF4J/Log4j2 (`ElectorService.java`, `LockCallbacks.java` throughout).
- Metrics/tracing coverage: only what Spring Boot Actuator provides by default; `management.endpoints.web.exposure.include=health,info` exposes `/actuator/health` and `/actuator/info` (`application.properties:2`). No custom metrics (Micrometer counters/timers) or distributed tracing found in source.
- Missing visibility gaps: no metric for lock-acquisition latency, reconcile duration, or label-drift count — only log lines. No Kubernetes liveness/readiness probe config found in this repo (would live in a Deployment manifest outside this codebase).

### 6) Evidence

- `src/main/java/io/jaredbrown/k8s/leader/configuration/K8sClientConfiguration.java`
- `src/main/java/io/jaredbrown/k8s/leader/configuration/RedisLockRegistryConfiguration.java`
- `src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`
- `README.md` (Securing Redis, health-gating sections)
- `.github/workflows/dt-sbom.yml`, `.github/workflows/release.yml`

## Extended Sections (Optional)

Not added — the integration surface is small (two runtime integrations) and fully covered above.
