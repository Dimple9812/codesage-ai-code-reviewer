package com.codesage.security;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.codesage.config.AppProperties;

@Configuration
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(AppProperties props, ObjectProvider<StringRedisTemplate> redis, Clock clock) {
        if ("redis".equalsIgnoreCase(props.rateLimit().backend())) {
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null) {
                return new RedisRateLimiter(template);
            }
        }
        return new InMemoryRateLimiter(clock);
    }
}
