package com.codesage.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Distributed fixed-window limiter backed by Redis INCR + EXPIRE. Fails open if Redis is unavailable. */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            String redisKey = "codesage:rl:" + key;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("Redis rate limiter unavailable, allowing request: {}", e.getClass().getSimpleName());
            return true;
        }
    }
}
