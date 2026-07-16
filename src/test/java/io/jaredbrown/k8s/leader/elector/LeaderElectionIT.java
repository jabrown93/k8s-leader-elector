package io.jaredbrown.k8s.leader.elector;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.jaredbrown.k8s.leader.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Boots real ElectorService instances (full Spring context) against a real Redis (Testcontainers)
// and a fabric8 mock Kubernetes API server, to exercise the wire-level behavior no unit test can:
// actual Redis lock contention/release, and the real shutdown-event ordering that
// TaskSchedulerConfiguration's acceptTasksAfterContextClose(true) exists to fix (see CLAUDE.md's
// Graceful Shutdown notes). Each "pod" is its own SpringApplicationBuilder-launched context so two
// pods can race for the same lock within a single test method.
@Testcontainers
@EnableKubernetesMockClient(crud = true, https = false)
class LeaderElectionIT {

    private static final String LABEL_KEY = "dns.jb.io/leader";
    private static final String SELECTOR_KEY = "app";
    private static final String SELECTOR_VALUE = "leader-elector-it";
    private static final String NAMESPACE = "test";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    // Injected by the mock-client extension (see class-level annotation); fresh per test method, so
    // pods created in one test never leak into another.
    private KubernetesMockServer mockServer;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void singlePod_acquiresLeadershipAndReleasesOnShutdown() {
        seedPod("pod-a");

        final ConfigurableApplicationContext first = startPod("pod-a", "lifecycle-lock");
        await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(currentLabel("pod-a")).isEqualTo("true"));

        first.close();
        contexts.remove(first);
        await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(currentLabel("pod-a")).isEqualTo("false"));

        // The lock was actually released in Redis (not just abandoned client-side): a fresh context
        // for the same pod/lock can immediately reacquire it.
        final ConfigurableApplicationContext second = startPod("pod-a", "lifecycle-lock");
        await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(currentLabel("pod-a")).isEqualTo("true"));
    }

    @Test
    void twoPods_failOverToSurvivorWhenLeaderStops() {
        seedPod("pod-a");
        seedPod("pod-b");

        final ConfigurableApplicationContext podA = startPod("pod-a", "failover-lock");
        await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(currentLabel("pod-a")).isEqualTo("true"));

        final ConfigurableApplicationContext podB = startPod("pod-b", "failover-lock");
        // pod-b stays a follower for a bit while pod-a holds the lock - proves Redis-side exclusion
        // between two independently, fully Spring-wired ElectorService instances.
        await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(currentLabel("pod-b")).isEqualTo("false"));

        podA.close();
        contexts.remove(podA);

        await()
                .atMost(AWAIT_TIMEOUT)
                .untilAsserted(() -> assertThat(currentLabel("pod-b")).isEqualTo("true"));
        assertThat(currentLabel("pod-a")).isEqualTo("false");
    }

    private ConfigurableApplicationContext startPod(final String podName, final String lockName) {
        final ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .initializers((ApplicationContextInitializer<GenericApplicationContext>) applicationContext ->
                        applicationContext.registerBean("mockKubernetesClient",
                                                         KubernetesClient.class,
                                                         (Supplier<KubernetesClient>) mockServer::createClient,
                                                         bd -> {
                                                             bd.setPrimary(true);
                                                             bd.setDestroyMethodName("close");
                                                         }))
                .properties("POD_NAME=" + podName,
                            "spring.data.redis.host=" + REDIS.getHost(),
                            "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                            "elector.label-key=" + LABEL_KEY,
                            "elector.lock-name=" + lockName,
                            "elector.selector-label-key=" + SELECTOR_KEY,
                            "elector.selector-label-value=" + SELECTOR_VALUE,
                            // Tuned well below production defaults so acquisition/renewal/failover
                            // settle in seconds rather than minutes.
                            "elector.lease-duration=3s",
                            "elector.renew-deadline=1s",
                            "elector.retry-period=1s")
                .run();
        contexts.add(context);
        return context;
    }

    private void seedPod(final String name) {
        try (KubernetesClient client = mockServer.createClient()) {
            client
                    .pods()
                    .inNamespace(NAMESPACE)
                    .resource(new PodBuilder()
                                      .withNewMetadata()
                                      .withName(name)
                                      .withNamespace(NAMESPACE)
                                      .addToLabels(SELECTOR_KEY, SELECTOR_VALUE)
                                      .endMetadata()
                                      .build())
                    .create();
        }
    }

    private String currentLabel(final String podName) {
        try (KubernetesClient client = mockServer.createClient()) {
            final Pod pod = client
                    .pods()
                    .inNamespace(NAMESPACE)
                    .withName(podName)
                    .get();
            return pod
                    .getMetadata()
                    .getLabels() == null
                    ? null
                    : pod
                            .getMetadata()
                            .getLabels()
                            .get(LABEL_KEY);
        }
    }
}
