# Codebase Structure

## Core Sections (Required)

### 1) Top-Level Map

| Path | Purpose | Evidence |
|------|---------|----------|
| `src/main/java/io/jaredbrown/k8s/leader/` | Application source (single Maven module) | directory tree |
| `src/main/java/io/jaredbrown/k8s/leader/elector/` | Core election domain: service, properties, callbacks, health probe | `elector/*.java` |
| `src/main/java/io/jaredbrown/k8s/leader/configuration/` | Spring `@Configuration` beans (K8s client, Redis lock registry, task scheduler) | `configuration/*.java` |
| `src/main/resources/` | Spring config (`application.properties`) and logging config (`log4j2.xml`) | `src/main/resources/` |
| `src/test/java/...` | JUnit 5/Mockito unit tests, mirrors main package structure | `src/test/java/io/jaredbrown/k8s/leader/` |
| `.github/workflows/` | CI, CodeQL, SBOM, PR license check, release automation (7 workflow files) | `.github/workflows/*.yml` |
| `Dockerfile` | Multi-stage build producing the runtime image (tini + JRE + jar) | `Dockerfile` |
| `Makefile` | `build`/`docker-build`/`docker-release` targets | `Makefile` |
| `.releaserc.json` / `package.json` / `package-lock.json` | semantic-release configuration and its npm-only tooling (CI, no runtime JS) | `.releaserc.json`, `package.json:4` |
| `docs/codebase/` | This generated documentation set | (this directory) |
| `.idea/` | JetBrains IDE project settings, including shared code style (`codeStyles/Project.xml`) | `.idea/` |

### 2) Entry Points

- Main runtime entry: `src/main/java/io/jaredbrown/k8s/leader/Application.java` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`, `main()` calls `SpringApplication.run`.
- Secondary entry points: none — this is a single-process sidecar binary with no CLI subcommands, workers, or scheduled jobs outside the one `ElectorService` lifecycle bean.
- How entry is selected: `pom.xml`'s `spring-boot-maven-plugin` sets `<mainClass>${main.class}</mainClass>` = `io.jaredbrown.k8s.leader.Application` (`pom.xml:21,105-111`); the Docker image runs the resulting jar directly (`Dockerfile:32-40`).

### 3) Module Boundaries

| Boundary | What belongs here | What must not be here |
|----------|-------------------|------------------------|
| `elector.ElectorService` | Lock acquisition/renewal/release lifecycle, retry/backoff scheduling, health-gate decision logic | Kubernetes API calls, label-patching logic (delegated to `LockCallbacks`) |
| `elector.LockCallbacks` | All Kubernetes Pod-label reads/writes, `POD_NAME` identity | Redis lock semantics, scheduling/timing decisions |
| `elector.HealthProbe` | Reading/interpreting the application's self-reported status file | Any application-specific health logic, no tool dependencies |
| `elector.ElectorProperties` | `elector.*` configuration binding + Bean Validation constraints | Business logic |
| `configuration/*` | Bean wiring only (`KubernetesClient`, `RedisLockRegistry`, `ThreadPoolTaskScheduler`, `Clock`) | Domain logic |

### 4) Naming and Organization Rules

- File naming pattern: PascalCase Java class-per-file, one public type per file (e.g. `ElectorService.java`, `HealthProbe.java`, `K8sClientConfiguration.java`).
- Directory organization pattern: organized by **layer/role within one feature domain** rather than by feature — `elector/` holds the domain logic, `configuration/` holds infrastructure wiring; there is no multi-feature package split since the app has one responsibility.
- Import aliasing or path conventions: none — standard Java package imports, no path aliases; single root package `io.jaredbrown.k8s.leader`.
- Test structure mirrors main: `src/test/java/io/jaredbrown/k8s/leader/elector/ElectorServiceTest.java` pairs with `src/main/.../elector/ElectorService.java`, same package.

### 5) Evidence

- `docs/codebase/.codebase-scan.txt` (directory tree)
- `src/main/java/io/jaredbrown/k8s/leader/Application.java`
- `pom.xml:103-111`

## Extended Sections (Optional)

Not added — single Maven module, no monorepo/workspace structure, no generated-output directories to distinguish from source.
