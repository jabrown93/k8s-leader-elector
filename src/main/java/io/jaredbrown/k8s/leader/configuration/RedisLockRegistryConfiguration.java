package io.jaredbrown.k8s.leader.configuration;

import io.jaredbrown.k8s.leader.elector.ElectorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;

@Configuration
public class RedisLockRegistryConfiguration {
    @Bean
    public RedisLockRegistry redisLockRegistry(RedisConnectionFactory redisConnectionFactory,
                                               ElectorProperties electorProperties) {
        return new RedisLockRegistry(
            redisConnectionFactory,
            electorProperties.getLockName() + "-lock-registry",
            electorProperties.getLeaseDuration()
        );
    }
}
