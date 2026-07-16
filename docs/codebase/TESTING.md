# Testing Patterns

## Core Sections (Required)

### 1) Test Stack and Commands

- Primary test framework: JUnit 5 (Jupiter), pulled in via `spring-boot-starter-test` (`pom.xml:72-76`).
- Assertion/mocking tools: JUnit Jupiter `Assertions` (`assertEquals`, `assertTrue`, `assertThrows`, etc.) + Mockito 5.x (`mockito-core`, `pom.xml:77-81`) via `@ExtendWith(MockitoExtension.class)`. No AssertJ/Hamcrest usage observed.
- Commands:

```bash
mvn -B verify     # CI command — compiles, runs all tests (ci.yml:31)
mvn test           # run tests only
mvn clean install  # full build incl. tests (Makefile `build` target)
```

No separate integration/e2e test command exists — there is only one test phase.

### 2) Test Layout

- Test file placement pattern: mirrors `src/main/java` package-for-package under `src/test/java` (e.g. `elector/ElectorService.java` ↔ `elector/ElectorServiceTest.java`).
- Naming convention: `<ClassUnderTest>Test.java`; test methods use `subjectUnderTest_expectedOutcome` (e.g. `taskScheduler_shouldBeSingleThreadedAndAcceptTasksAfterContextClose`, `TaskSchedulerConfigurationTest.java:13`).
- Setup files and where they run: no shared/global test base class or `@SpringBootTest` context found — every test class is a plain unit test with `@BeforeEach setUp()` constructing the class under test directly with mocked collaborators (no Spring context loaded in tests, keeping them fast).

### 3) Test Scope Matrix

| Scope | Covered? | Typical target | Notes |
|-------|----------|----------------|-------|
| Unit | Yes | `ElectorService`, `LockCallbacks`, `HealthProbe`, `ElectorProperties`, `TaskSchedulerConfiguration` — every main class has a matching test class | All collaborators (Redis lock registry, K8s client, task scheduler, clock) are mocked; `ElectorPropertiesTest` uses a real Jakarta `Validator` to exercise Bean Validation constraints end-to-end (`ElectorPropertiesTest.java:21-33`) |
| Integration | No | — | No test hits a real Redis instance or a real (or Testcontainers-backed) Kubernetes API; `KubernetesClient` and `RedisLockRegistry` are always Mockito mocks |
| E2E | No | — | No end-to-end test exercising the full sidecar against a live cluster; would have to be validated manually/in a real deployment |

### 4) Mocking and Isolation Strategy

- Main mocking approach: constructor injection of Mockito `@Mock` doubles (`@ExtendWith(MockitoExtension.class)`), never Spring's test context — all five test classes instantiate the class under test directly (e.g. `new ElectorService(callbacks, electorProperties, lockRegistry, taskScheduler, healthProbe, clock)`, `ElectorServiceTest.java:69`).
- Isolation guarantees: `lenient().when(...)` is used deliberately for default stubs that not every test case exercises, avoiding Mockito's strict-stubbing `UnnecessaryStubbingException` while keeping each test's *own* specific stubs strict (`ElectorServiceTest.java:73-90`, `LockCallbacksTest.java:63-81`, `HealthProbeTest.java:93,109-114`). A custom `MutableClock` (test-only class referenced in `ElectorServiceTest.java:62`) replaces `Clock.systemUTC()` for deterministic time-based assertions on the deadlock-grace and staleness logic. `@TempDir` (JUnit) provides a real, isolated filesystem directory for `HealthProbeTest` rather than mocking `java.nio.file` (`HealthProbeTest.java:27-28`).
- Common failure mode in tests: `[TODO]` — no flaky-test history or known-flaky markers found; cannot verify without CI run history beyond what's in this repo.

### 5) Coverage and Quality Signals

- Coverage tool + threshold: JaCoCo (`jacoco-maven-plugin`), bound to `test` (report) and `verify` (check) — `mvn verify` fails if line coverage drops below 85% (`pom.xml`). Report generated at `target/site/jacoco/index.html`.
- Current reported coverage: 91.75% lines (289/315) as measured when the gate was introduced; `ElectorService` 96.2%, `LockCallbacks` 100%, `HealthProbe` 90.3% — the two `@Configuration` bean-factory classes (`K8sClientConfiguration`, `RedisLockRegistryConfiguration`) show 0% since their `@Bean` methods only execute in a live Spring context, not under the mock-based unit tests.
- Known gaps/flaky areas: no integration-level test against real Redis or a real/fake Kubernetes API server (e.g. via Testcontainers or Fabric8's `KubernetesServer` test fixture) — all Kubernetes/Redis interaction is verified purely through Mockito interaction verification, so a contract drift in either client library (e.g. a Fabric8 fluent-API change) would not be caught by tests, only by `mvn verify` failing to compile or by production behavior.

### 6) Evidence

- `pom.xml:72-81`
- `src/test/java/io/jaredbrown/k8s/leader/elector/ElectorServiceTest.java`
- `src/test/java/io/jaredbrown/k8s/leader/elector/HealthProbeTest.java`
- `.github/workflows/ci.yml`

## Extended Sections (Optional)

Not added — one consistent test pattern is used across the whole suite; no per-framework variance to document.
