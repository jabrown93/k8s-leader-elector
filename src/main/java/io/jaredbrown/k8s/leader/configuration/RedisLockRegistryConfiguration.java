package io.jaredbrown.k8s.leader.configuration;

import io.jaredbrown.k8s.leader.elector.ElectorProperties;
import jakarta.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;

@Slf4j
@Configuration
public class RedisLockRegistryConfiguration {
    @Bean
    public RedisLockRegistry redisLockRegistry(@Nonnull final RedisConnectionFactory redisConnectionFactory,
                                               @Nonnull final ElectorProperties electorProperties) {
        return new RedisLockRegistry(redisConnectionFactory,
                                     electorProperties.getLockName() + "-lock-registry",
                                     electorProperties.getLeaseDuration());
    }
}
