package com.dmg.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Atomic dual-window rate-limit check via Lua.
     *
     * KEYS: [minuteKey, hourKey]
     * ARGV: [minuteLimit, hourLimit]
     *
     * Uses fixed windows (bucket keyed by current minute/hour epoch).
     * Atomically increments both counters; if either exceeds its limit,
     * decrements both and returns 0 (denied). Returns 1 on success.
     *
     * Fixed window is slightly less accurate than sliding window but is
     * O(1) in space and predictable under burst load.
     */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        String script = """
                local m_count = tonumber(redis.call('INCR', KEYS[1]))
                if m_count == 1 then redis.call('EXPIRE', KEYS[1], 62) end

                local h_count = tonumber(redis.call('INCR', KEYS[2]))
                if h_count == 1 then redis.call('EXPIRE', KEYS[2], 3602) end

                if m_count > tonumber(ARGV[1]) or h_count > tonumber(ARGV[2]) then
                    redis.call('DECR', KEYS[1])
                    redis.call('DECR', KEYS[2])
                    return 0
                end
                return 1
                """;
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}
