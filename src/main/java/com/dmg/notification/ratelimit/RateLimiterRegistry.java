package com.dmg.notification.ratelimit;

import com.dmg.notification.domain.RateLimitConfig;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.repository.RateLimitConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter with two tiers:
 *
 * 1. Redis (primary) — distributed, consistent across multiple service instances.
 *    Uses a Lua script for atomic dual-window (per-minute + per-hour) check.
 *    Keys: rl:{tenantId}:{channel}:m:{minuteEpoch}  and  rl:{tenantId}:{channel}:h:{hourEpoch}
 *
 * 2. In-memory TokenBucket (fallback) — used when Redis is unavailable.
 *    Provides per-process rate limiting; sufficient for single-instance deployments.
 *
 * The Redis fixed-window approach is O(1) in memory and handles flash-sale bursts well:
 * if a tenant sends 10,000 notifications in the first second of a minute, tokens are
 * exhausted immediately and subsequent calls fail fast (no queuing / unbounded delay).
 */
@Slf4j
@Component
public class RateLimiterRegistry {

    private static final int DEFAULT_RPM = 60;
    private static final int DEFAULT_RPH = 1000;

    private final RateLimitConfigRepository rateLimitConfigRepository;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private RedisScript<Long> rateLimitScript;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterRegistry(RateLimitConfigRepository rateLimitConfigRepository) {
        this.rateLimitConfigRepository = rateLimitConfigRepository;
    }

    public boolean tryConsume(UUID tenantId, Channel channel) {
        if (redisTemplate != null && rateLimitScript != null) {
            try {
                return tryConsumeRedis(tenantId, channel);
            } catch (Exception ex) {
                log.warn("Redis rate-limit check failed, falling back to in-memory. reason={}", ex.getMessage());
            }
        }
        return tryConsumeInMemory(tenantId, channel);
    }

    public void evict(UUID tenantId, Channel channel) {
        buckets.remove(bucketKey(tenantId, channel));
        if (redisTemplate != null) {
            // Evict current minute and hour keys
            long nowMs = System.currentTimeMillis();
            String base = "rl:" + tenantId + ":" + channel.name();
            redisTemplate.delete(base + ":m:" + (nowMs / 60_000));
            redisTemplate.delete(base + ":h:" + (nowMs / 3_600_000));
        }
        log.debug("Evicted rate limit state for tenant={} channel={}", tenantId, channel);
    }

    // --- Redis implementation ---

    private boolean tryConsumeRedis(UUID tenantId, Channel channel) {
        Optional<RateLimitConfig> cfg = rateLimitConfigRepository.findByTenantIdAndChannel(tenantId, channel);
        int rpm = cfg.map(RateLimitConfig::getRequestsPerMinute).orElse(DEFAULT_RPM);
        int rph = cfg.map(RateLimitConfig::getRequestsPerHour).orElse(DEFAULT_RPH);

        long nowMs = System.currentTimeMillis();
        String base = "rl:" + tenantId + ":" + channel.name();
        String minuteKey = base + ":m:" + (nowMs / 60_000);
        String hourKey   = base + ":h:" + (nowMs / 3_600_000);

        Long result = redisTemplate.execute(
                rateLimitScript,
                List.of(minuteKey, hourKey),
                String.valueOf(rpm),
                String.valueOf(rph)
        );
        boolean allowed = Long.valueOf(1L).equals(result);
        if (!allowed) {
            log.debug("Redis rate limit exceeded tenant={} channel={}", tenantId, channel);
        }
        return allowed;
    }

    // --- In-memory fallback ---

    private boolean tryConsumeInMemory(UUID tenantId, Channel channel) {
        String key = bucketKey(tenantId, channel);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> loadBucket(tenantId, channel));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.debug("In-memory rate limit exceeded tenant={} channel={}", tenantId, channel);
        }
        return allowed;
    }

    private TokenBucket loadBucket(UUID tenantId, Channel channel) {
        return rateLimitConfigRepository
                .findByTenantIdAndChannel(tenantId, channel)
                .map(cfg -> new TokenBucket(cfg.getRequestsPerMinute(), cfg.getRequestsPerHour()))
                .orElseGet(() -> new TokenBucket(DEFAULT_RPM, DEFAULT_RPH));
    }

    private String bucketKey(UUID tenantId, Channel channel) {
        return tenantId + ":" + channel.name();
    }
}
