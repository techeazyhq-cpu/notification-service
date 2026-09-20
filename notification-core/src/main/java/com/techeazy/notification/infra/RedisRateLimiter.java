package com.techeazy.notification.infra;

import com.techeazy.notification.port.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token bucket evaluated atomically in Redis (Lua). Time comes from the Redis server so that
 * clock skew between application nodes cannot distort the bucket.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final String LUA = """
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(d[1])
            local ts = tonumber(d[2])
            if tokens == nil then tokens = burst; ts = now end
            tokens = math.min(burst, tokens + (math.max(0, now - ts) / 1000.0) * rate)
            local allowed = 0
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 - tokens) / rate * 1000)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(burst / rate * 1000) * 2 + 1000)
            return {allowed, wait}
            """;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision tryAcquire(String key, double ratePerSecond, int burst) {
        List<?> r = redis.execute(script, List.of("rl:" + key), Double.toString(ratePerSecond), Integer.toString(burst));
        if (r == null || r.size() < 2) return Decision.GRANTED; // fail open: never block traffic on limiter faults
        boolean allowed = ((Number) r.get(0)).longValue() == 1;
        return allowed ? Decision.GRANTED : new Decision(false, ((Number) r.get(1)).longValue());
    }
}
