# Coding Conventions

## Core Sections (Required)

### 1) Naming Rules

| Item | Rule | Example | Evidence |
|------|------|---------|----------|
| Files | PascalCase, one public type per file, `*Test` suffix for test classes | `ElectorService.java` / `ElectorServiceTest.java` | `src/main/java/.../elector/`, `src/test/java/.../elector/` |
| Functions/methods | camelCase, verb-first, private helpers named for the action (`scheduleRetry`, `releaseLockIfHeld`, `deadlockGraceExceeded`) | `ElectorService.java:255-273` | `ElectorService.java` |
| Types/interfaces | PascalCase; `*Properties` for `@ConfigurationProperties` classes, `*Configuration` for `@Configuration` classes, `*Callbacks` for the K8s side-effect component | `ElectorProperties`, `RedisLockRegistryConfiguration`, `LockCallbacks` | package listing |
| Constants/env vars | `UPPER_SNAKE_CASE` for `static final` fields (`RELEASE_TIMEOUT`, `REQUEST_TIMEOUT_MILLIS`); env vars are the Spring-relaxed-binding form of `elector.*` properties, e.g. `elector.labelKey` → `ELECTOR_LABEL_KEY` | `ElectorService.java:29`, `K8sClientConfiguration.java:19-20`, `README.md:22-23` | as cited |
| Test methods | `methodUnderTest_expectedBehavior` (snake-ish camelCase with underscore separating subject/expectation) | `isHealthy_returnsFalseWhenFileStale`, `shouldFailValidationWhenLabelKeyIsNull` | `HealthProbeTest.java:69`, `ElectorPropertiesTest.java:85` |

### 2) Formatting and Linting

- Formatter: no CI-enforced formatter/linter config found in the repo (no `.editorconfig`, no Checkstyle/Spotless/PMD Maven plugin). The only formatting rules present are JetBrains IDE-local settings in `.idea/codeStyles/Project.xml` (final locals/parameters generation, import-on-demand thresholds, Java arrangement rules for getters/setters/overrides) — these are IDE conventions, not build-enforced.
- Linter: none configured (`docs/codebase/.codebase-scan.txt` — "No linting or formatting config files found in project root").
- Most relevant *observed* (not enforced) conventions from reading source: `final` on locals and parameters everywhere; 4-space indentation; method-chain calls broken one-call-per-line when wrapped (e.g. `ElectorPropertiesTest`, `LockCallbacksTest` builder-style Mockito stubs).
- Run commands: `mvn -B verify` is the only CI-enforced gate (`.github/workflows/ci.yml:30-31`) — it runs compilation + tests, not a style check.

### 3) Import and Module Conventions

- Import grouping/order: standard, non-wildcard imports (`PACKAGES_TO_USE_IMPORT_ON_DEMAND` is empty in the IDE code style, meaning wildcard imports are discouraged) — confirmed by every source file using explicit imports.
- Alias vs relative import policy: N/A (Java has no import aliasing); package-qualified imports throughout.
- Public exports/barrel policy: N/A — no module/package-export mechanism used; all cross-class use is plain Spring dependency injection (`@RequiredArgsConstructor` + `@Autowired`-by-constructor-implicit).

### 4) Error and Logging Conventions

- Error strategy by layer: `ElectorService` catches broadly at the loop boundary (`lockLoop`, `refreshLock`) and converts any exception into a retry/backoff or `handleLockLost()` rather than propagating (`ElectorService.java:210-220,322-325`). `LockCallbacks` never throws from its public label-mutation methods — `KubernetesClientException` is caught and logged, with the sole exception being `validateSelfPodName()`, which deliberately throws `IllegalStateException` at `@PostConstruct` to fail fast on missing `POD_NAME` (`LockCallbacks.java:41-46`). `HealthProbe.isHealthy()` never throws — any `IOException` is caught and treated as "unhealthy" (`HealthProbe.java:85-88`).
- Logging style: Lombok `@Slf4j` on every component that logs; parameterized SLF4J messages (`log.info("...{}...", arg)`), never string concatenation. Distinct log levels used deliberately: `info` for normal state transitions, `warn` for degraded-but-handled conditions (stale health file, failed release retry), `error` for failures that are logged but intentionally swallowed. Log4j2 backend with pattern `%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n` (`log4j2.xml:5`).
- Sensitive-data redaction rules: none explicit in code — the app does not log secrets; Redis/K8s credentials are never read or logged directly (see INTEGRATIONS.md).

### 5) Testing Conventions

- Test file naming/location rule: `<ClassUnderTest>Test.java`, same package as the class under test, under `src/test/java/...` mirroring `src/main/java/...`.
- Mocking strategy norm: `@ExtendWith(MockitoExtension.class)` + `@Mock` fields + `lenient().when(...)` for stubs not used by every test in the class (avoids `UnnecessaryStubbingException` noise) — consistent across `ElectorServiceTest`, `LockCallbacksTest`, `HealthProbeTest`. `ReflectionTestUtils` used to set private fields not exposed via constructor (`selfPodName` in `LockCallbacksTest.java:61`, scheduler internals in `TaskSchedulerConfigurationTest.java:18,23`). A custom `MutableClock` test double is injected via the `Clock` bean for deterministic time-based assertions (`ElectorServiceTest.java:62,68`, enabled by `TaskSchedulerConfiguration.clock()` being a real bean rather than `Clock.systemUTC()` inlined).
- Coverage expectation: JaCoCo enforces a minimum 85% line-coverage ratio on `mvn verify` (`pom.xml`, `jacoco-maven-plugin` `check` execution bound to the `verify` phase).

### 6) Evidence

- `.idea/codeStyles/Project.xml`
- `src/test/java/io/jaredbrown/k8s/leader/elector/ElectorServiceTest.java`
- `src/main/java/io/jaredbrown/k8s/leader/elector/LockCallbacks.java`
- `src/main/resources/log4j2.xml`

## Extended Sections (Optional)

Not added — the codebase is small and stylistically uniform; no conflicting conventions were observed across files.
