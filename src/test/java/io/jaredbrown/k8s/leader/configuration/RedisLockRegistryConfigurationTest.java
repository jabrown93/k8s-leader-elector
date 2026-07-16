package io.jaredbrown.k8s.leader.configuration;

import io.jaredbrown.k8s.leader.elector.ElectorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLockRegistryConfigurationTest {

    @Mock
    private RedisConnectionFactory redisConnectionFactory;

    @Mock
    private ElectorProperties electorProperties;

    @Test
    void redisLockRegistry_shouldUseConfiguredLockNameAndLeaseDuration() {
        when(electorProperties.getLockName()).thenReturn("test-lock");
        when(electorProperties.getLeaseDuration()).thenReturn(Duration.ofSeconds(42));

        final RedisLockRegistry registry =
                new RedisLockRegistryConfiguration().redisLockRegistry(redisConnectionFactory, electorProperties);

        assertEquals("test-lock-lock-registry", ReflectionTestUtils.getField(registry, "registryKey"));
        assertEquals(Duration.ofSeconds(42), ReflectionTestUtils.getField(registry, "expireAfter"));
    }
}
