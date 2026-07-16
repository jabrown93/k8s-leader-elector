# Codebase Concerns

## Core Sections (Required)

### 1) Top Risks (Prioritized)

| Severity | Concern | Evidence | Impact | Suggested action |
|----------|---------|----------|--------|------------------|
| High | Redis lock has no auth/TLS configured by default; any network client that can reach Redis can forge/steal leadership via a raw `SET` on the lock key | `README.md:36-44`, `application.properties:3` (no password/ssl props), `RedisLockRegistryConfiguration.java` | Leadership can be spoofed by any workload with Redis network access | Already documented as an operator responsibility in README; no code enforcement exists — confirm this is acceptable for every deployment environment, not just trusted clusters (see `[ASK USER]` #1) |
| Medium | No integration test exercises a real (or fake/Testcontainers) Redis or Kubernetes API — all tests mock `RedisLockRegistry`/`KubernetesClient` | `TESTING.md` §3, all `src/test/java` files | Contract drift in Fabric8/Spring Integration Redis client behavior (e.g. a fluent-API or Lua-script semantics change on upgrade) would not be caught by the test suite | Consider a Testcontainers-backed Redis test for the acquire/renew/release cycle, and/or Fabric8's `KubernetesServer` mock for `LockCallbacks` |
| Medium | No coverage tool/threshold configured | `pom.xml` (no JaCoCo plugin), `ci.yml` (no coverage step) | Regressions in untested branches (e.g. rare error-handling paths) can land unnoticed | Decide whether coverage tracking is worth adding at this project stage (`[ASK USER]` #2) |
| Low | Stale comment in `dt-sbom.yml` still refers to `fossa.yml` as "stays in place until DT is proven out," but `fossa.yml` was already deleted (commit `69ecced`, "Delete .github/workflows/fossa.yml") | `.github/workflows/dt-sbom.yml:3-4`, `git log --follow -- .github/workflows/fossa.yml` | Misleading comment for future maintainers about migration status | Update/remove the stale comment in `dt-sbom.yml` |
| Low | No linter/formatter enforced in CI (only JetBrains IDE-local style settings exist) | `docs/codebase/.codebase-scan.txt` ("No linting or formatting config files found"), `.idea/codeStyles/Project.xml` | Style drift possible across contributors/IDEs not using the shared `.idea` profile | Consider a Maven-bound formatter (e.g. Spotless) if multiple contributors are expected |

### 2) Technical Debt

| Debt item | Why it exists | Where | Risk if ignored | Suggested fix |
|-----------|---------------|-------|-----------------|---------------|
| No explicit Redis command timeout override (unlike the deliberate 2s/1-retry bound on the Kubernetes client) | `K8sClientConfiguration` was clearly tuned to protect the single scheduler thread from a slow K8s API, but no equivalent tuning was found for Redis/Lettuce | `RedisLockRegistryConfiguration.java` (no timeout config), `ElectorService.renewLockOnce`/`stillOwnsLock` call `lockRegistry.renewLock` inline on the same thread | A slow/hanging Redis call could stall the scheduler thread past the lease or the 5s shutdown release window, the exact failure mode `K8sClientConfiguration`'s comments describe avoiding for K8s | Confirm whether Lettuce's default connection/command timeout is already tight enough, or add an explicit `spring.data.redis.timeout` (`[ASK USER]` #3) |
| `ElectorService.java` (389 lines) concentrates acquisition, renewal, health-gating, and scheduling logic in one class | Deliberate: keeps the single-thread lock-ownership invariant simple to reason about (per its own doc comments) | `ElectorService.java` | Low as-is — cohesive single responsibility (the lock state machine); would become a real debt item only if more responsibilities are added | No action needed now; watch file size if new features land here |

### 3) Security Concerns

| Risk | OWASP category (if applicable) | Evidence | Current mitigation | Gap |
|------|--------------------------------|----------|--------------------|-----|
| Unauthenticated Redis backing the leader lock | A07:2021 – Identification and Authentication Failures (adjacent; this is an infra trust-boundary issue, not an app auth bug) | `README.md:36-44` | Documented operator guidance ("run against a Redis instance that untrusted workloads cannot reach") | No code-level enforcement (e.g. failing startup if `spring.data.redis.password` unset in a "production" profile) |
| Kubernetes RBAC scope for the service account is defined outside this repo | N/A | `K8sClientConfiguration.java` uses `Config.autoConfigure(null)` (in-cluster SA token) | N/A — RBAC lives in cluster manifests, not this codebase | `[TODO]` — cannot verify least-privilege without the Deployment/RBAC manifests, which are not in this repo |
| Image supply chain | A08:2021 – Software and Data Integrity Failures | `.github/workflows/release.yml` (cosign keyless signing, SBOM+provenance attached), `.github/workflows/dt-sbom.yml`, `.github/workflows/codeql.yml` | Strong: CodeQL static analysis, SBOM generation + Dependency-Track upload, keyless cosign signing of every release image | None identified |

### 4) Performance and Scaling Concerns

| Concern | Evidence | Current symptom | Scaling risk | Suggested improvement |
|---------|----------|-----------------|-------------|-----------------------|
| Single-threaded scheduler processes lock ops and full pod-label reconciliation sequentially | `TaskSchedulerConfiguration.java:20`, `LockCallbacks.reconcileLeaderLabels` | None observed — deliberate design tradeoff, documented extensively in code comments | For a StatefulSet/Deployment with a very large pod count, one reconcile pass patches pods one at a time in a loop (`LockCallbacks.java:78-97`), so reconcile duration scales linearly with pod count and must stay well under `renewDeadline` | Acceptable at typical sidecar fleet sizes (single digits to low tens of pods per selector); would need re-evaluation only for very large fleets |

### 5) Fragile/High-Churn Areas

| Area | Why fragile | Churn signal | Safe change strategy |
|------|-------------|-------------|----------------------|
| `.github/workflows/release.yml` | Coordinates multi-platform Docker build, GHCR push, and keyless cosign signing with a concurrency group to avoid floating-tag races | 23 changes in last 90 days (highest churn file in repo) | Changes here should be tested via a `beta` release before touching `main`-triggered behavior; the concurrency-group and `--force-with-lease` logic is subtle and documented in-file — read the comments before altering |
| `.github/workflows/dt-sbom.yml` | Two-job split (untrusted hosted runner → trusted in-cluster runner) specifically to keep build-plugin execution off the privileged runner | 10 changes in last 90 days | Preserve the job boundary (never move SBOM *generation* onto the in-cluster runner) when modifying |
| `ElectorService.java` / `LockCallbacksTest.java` / `ElectorServiceTest.java` (core elector logic + its tests) | Encodes multiple interacting timing/ownership invariants (single-thread confinement, TOCTOU-guarded reconcile, deadlock escape hatch) | 4 changes each in last 90 days, concentrated around the same recent feature work (`a7f6d8c`, `22c7f59`) | Any change to scheduling/threading must preserve the single-scheduler-thread invariant; run the full `ElectorServiceTest` suite (792 lines, most extensive test file) after any change here |

### 6) `[ASK USER]` Questions

1. **[ASK USER]** Is running without Redis auth/TLS (`spring.data.redis.password`/`spring.data.redis.ssl.enabled` unset) acceptable for all intended deployment environments, or should the app enforce/require auth in some profile? The README treats this as purely an operator responsibility today.
2. **[ASK USER]** Is the absence of a code-coverage tool/threshold a deliberate choice for this project's current stage ("under active development, bugs are likely" per `README.md:9`), or is coverage tracking wanted going forward?
3. **[ASK USER]** Was the lack of an explicit Redis command/connection timeout (contrasted with the deliberately tuned 2s/1-retry Kubernetes client bound) an intentional decision, or an oversight worth closing given the single-scheduler-thread design's sensitivity to any blocking call?
4. **[ASK USER]** The `dt-sbom.yml` comment describing `fossa.yml` as still in place is now stale (the file was deleted). Should this comment simply be updated/removed, or does it reflect an intended future re-add of FOSSA?

### 7) Evidence

- `docs/codebase/.codebase-scan.txt` (HIGH-CHURN FILES, TODO/FIXME/HACK sections)
- `README.md` (Securing Redis section)
- `.github/workflows/dt-sbom.yml`, `.github/workflows/release.yml`
- `src/main/java/io/jaredbrown/k8s/leader/elector/ElectorService.java`
- `src/main/java/io/jaredbrown/k8s/leader/configuration/K8sClientConfiguration.java`

## Extended Sections (Optional)

Not added — the risk set above is small and fully enumerated; no full bug inventory or cost/effort estimation was requested.
