/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.infra;

import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token bucket evaluated atomically in Redis (Lua). Time comes from the Redis server so that
 * clock skew between application nodes cannot distort the bucket.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    public static final String ERRORS_METRIC = "notification.rate_limiter.errors";
    static final Duration SKIP_AFTER_ERROR = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String LUA = """
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local reserve = tonumber(ARGV[3])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(d[1])
            local ts = tonumber(d[2])
            if tokens == nil then tokens = burst; ts = now end
            tokens = math.min(burst, tokens + (math.max(0, now - ts) / 1000.0) * rate)
            local allowed = 0
            local wait = 0
            if tokens - reserve >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 + reserve - tokens) / rate * 1000)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(burst / rate * 1000) * 2 + 1000)
            return {allowed, wait}
            """;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Counter errors;
    private final AtomicReference<Instant> skipRedisUntil = new AtomicReference<>(Instant.MIN);

    @Autowired
    public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this(redis, meters, Clock.systemUTC());
    }

    RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meters, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        this.errors = Counter.builder(ERRORS_METRIC)
                .description("Rate-limit checks that failed open because Redis could not answer")
                .register(meters);
    }

    /**
     * Fails open: when Redis cannot answer, the call is let through and counted in {@value #ERRORS_METRIC}, and Redis
     * is left alone for {@link #SKIP_AFTER_ERROR}, so an outage neither stops the platform nor adds a timeout to every
     * call. Limits are not enforced meanwhile, which is why the counter has an alert (see ADR-024).
     */
    @Override
    public Decision tryAcquire(String key, double ratePerSecond, int burst, int reserve) {
        if (clock.instant().isBefore(skipRedisUntil.get())) {
            return Decision.GRANTED;
        }
        try {
            List<?> reply = redis.execute(script, List.of("rl:" + key), Double.toString(ratePerSecond),
                    Integer.toString(burst), Integer.toString(reserve));
            if (reply == null || reply.size() < 2) {
                return failOpen("an unexpected reply from the rate-limit script", null);
            }
            boolean allowed = ((Number) reply.get(0)).longValue() == 1;
            return allowed ? Decision.GRANTED : new Decision(false, ((Number) reply.get(1)).longValue());
        } catch (RuntimeException failure) {
            return failOpen(failure.getMessage(), failure);
        }
    }

    private Decision failOpen(String reason, RuntimeException failure) {
        errors.increment();
        skipRedisUntil.set(clock.instant().plus(SKIP_AFTER_ERROR));
        LOG.warn("Rate limiting skipped for {} s: {}", SKIP_AFTER_ERROR.toSeconds(), reason, failure);
        return Decision.GRANTED;
    }
}
