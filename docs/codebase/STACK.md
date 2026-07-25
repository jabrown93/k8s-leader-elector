# Technology Stack

## Core Sections (Required)

### 1) Runtime Summary

| Area | Value | Evidence |
|------|-------|----------|
| Primary language | Java 25 (`maven.compiler.release=25`) | `pom.xml` |
| Runtime + version | JVM 25, Amazon Corretto distribution (`dhi.io/amazoncorretto:25.0.3-alpine3.24*`) | `Dockerfile,14`, `.github/workflows/ci.yml` (`distribution: corretto`, `java-version: '25'`) |
| Package manager | Maven (application); npm (CI-only release tooling, no runtime JS) | `pom.xml`, `package.json` |
| Module/build system | Maven, parented on `spring-boot-starter-parent:4.1.0` | `pom.xml` |

### 2) Production Frameworks and Dependencies

| Dependency | Version | Role in system | Evidence |
|------------|---------|----------------|----------|
| Spring Boot | 4.1.0 (parent BOM) | Application framework, `SmartLifecycle`, config binding, actuator | `pom.xml` |
| Spring Integration Redis (`spring-integration-redis`) | managed by Boot BOM | `RedisLockRegistry` — the distributed lock primitive | `pom.xml`, `RedisLockRegistryConfiguration.java` |
| Spring Boot Data Redis (`spring-boot-starter-data-redis`) | managed by Boot BOM | Redis connection factory backing the lock registry | `pom.xml` |
| Spring Cloud Kubernetes (`spring-cloud-starter-kubernetes-client-all`) | via `spring-cloud-dependencies:2025.1.2` | Kubernetes-aware Spring config/discovery support | `pom.xml` |
| Fabric8 `kubernetes-client` | managed by Boot BOM | K8s API client used to patch Pod labels | `pom.xml`, `LockCallbacks.java`, `K8sClientConfiguration.java` |
| Lombok | 1.18.46 | Boilerplate reduction (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`), compile-time only | `pom.xml` |
| Log4j 2 (`log4j-core`/`log4j-api`) | via `log4j-bom:2.26.1` | Logging backend (Spring's default SLF4J binding is excluded implicitly by using log4j2) | `pom.xml`, `src/main/resources/log4j2.xml` |
| `spring-boot-starter-validation` | managed by Boot BOM | Jakarta Bean Validation on `ElectorProperties` | `pom.xml`, `ElectorProperties.java` |

### 3) Development Toolchain

| Tool | Purpose | Evidence |
|------|---------|----------|
| JUnit 5 (Jupiter) + Mockito | Unit testing (`spring-boot-starter-test`, `mockito-core`) | `pom.xml`, all files in `src/test/java` |
| JaCoCo (`jacoco-maven-plugin`) | Line-coverage report + enforced 85% minimum gate on `mvn verify` | `pom.xml` (`jacoco-maven-plugin` execution block) |
| `maven-compiler-plugin` 3.15.0 | Compiles with Lombok annotation processor | `pom.xml` |
| `spring-boot-maven-plugin` | Builds the executable/thin jar, sets main class | `pom.xml` |
| `cyclonedx-maven-plugin` 2.9.2 | Generates CycloneDX SBOM (`target/sbom.cdx.json`), invoked explicitly by CI, not bound to the default lifecycle | `pom.xml`, `.github/workflows/dt-sbom.yml` |
| semantic-release (npm, `package.json`) | CI-only conventional-commit versioning/release automation — no runtime JS in the repo | `package.json`, `.releaserc.json` |
| No linter/formatter config found in repo | IDE-level formatting rules exist only in `.idea/codeStyles/Project.xml` (JetBrains IDE settings, not an enforced CI check) | `.idea/codeStyles/Project.xml` |

### 4) Key Commands

```bash
mvn clean install     # full build incl. tests (Makefile `build` target)
mvn -B verify          # CI test/build command
mvn clean package      # build only
make docker-build       # multi-arch (linux/amd64,linux/arm64) image build, no push
make docker-release     # multi-arch build + push to ghcr.io/jabrown93
```

### 5) Environment and Config

- Config sources: `src/main/resources/application.properties` (defaults: `server.shutdown=graceful`, `management.endpoints.web.exposure.include=health,info`, `spring.data.redis.host=localhost`); all `elector.*` properties are also settable via env vars through Spring relaxed binding (e.g. `ELECTOR_LABEL_KEY`).
- Required env vars: `POD_NAME` (required, no default — app fails startup if blank/missing, see `LockCallbacks.java`); `ELECTOR_LABEL_KEY`, `ELECTOR_LOCK_NAME`, `ELECTOR_SELECTOR_LABEL_KEY`, `ELECTOR_SELECTOR_LABEL_VALUE` (all `@NotBlank`, no defaults, see `ElectorProperties.java`); `SPRING_DATA_REDIS_HOST` (defaults to `localhost`).
- Deployment/runtime constraints: runs as a sidecar container in-cluster; JVM heap sized 50–75% of container memory via `-XX:+UseContainerSupport`/`MaxRAMPercentage`/`InitialRAMPercentage`; uses `tini` as PID 1 for signal handling (`Dockerfile,22-23`); requires in-cluster Kubernetes API access (default kubeconfig or in-cluster service account) and reachability to a Redis instance.

### 6) Evidence

- `pom.xml`
- `Dockerfile`
- `.github/workflows/ci.yml`
- `src/main/resources/application.properties`
- `.releaserc.json`

## Extended Sections (Optional)

Not added — repo is small and single-module; the core sections fully cover the stack.
