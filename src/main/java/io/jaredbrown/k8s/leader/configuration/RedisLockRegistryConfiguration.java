package io.jaredbrown.k8s.leader.configuration;

import io.jaredbrown.k8s.leader.elector.ElectorProperties;
import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;

/**
 * Builds the {@link RedisLockRegistry} bean backing distributed leader election.
 */
@Configuration
public class RedisLockRegistryConfiguration {
    /**
     * @param redisConnectionFactory connection factory for the target Redis instance
     * @param electorProperties      supplies the lock name (registry key is {@code
     *                                <lockName>-lock-registry}) and the lease duration
     * @return a {@link RedisLockRegistry} scoped to this application's lock
     */
    @Bean
    @Nonnull
    public RedisLockRegistry redisLockRegistry(@Nonnull final RedisConnectionFactory redisConnectionFactory,
                                               @Nonnull final ElectorProperties electorProperties) {
        return new RedisLockRegistry(redisConnectionFactory,
                                     electorProperties.getLockName() + "-lock-registry",
                                     electorProperties.getLeaseDuration());
    }
}
