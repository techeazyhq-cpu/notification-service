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

import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the token-bucket script against a real Redis. A reserve keeps the last tokens of a bucket for priority traffic
 * (one-time passwords, ADR-033): ordinary sends stop at the reserve, priority sends may take it.
 */
@Testcontainers
class RedisRateLimiterScriptTest {

    private static final double ALMOST_NO_REFILL = 0.001;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static RedisRateLimiter limiter;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        limiter = new RedisRateLimiter(redis, new SimpleMeterRegistry());
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @Test
    void withoutAReserveTheWholeBurstIsAvailable() {
        String key = freshKey();

        long granted = grantedOf(10, () -> limiter.tryAcquire(key, ALMOST_NO_REFILL, 10));

        assertThat(granted).isEqualTo(10);
        assertThat(limiter.tryAcquire(key, ALMOST_NO_REFILL, 10).allowed()).isFalse();
    }

    @Test
    void ordinarySendsStopAtTheReserveAndPrioritySendsMayTakeIt() {
        String key = freshKey();

        long ordinary = grantedOf(10, () -> limiter.tryAcquire(key, ALMOST_NO_REFILL, 10, 2));
        long priority = grantedOf(10, () -> limiter.tryAcquire(key, ALMOST_NO_REFILL, 10, 0));

        assertThat(ordinary).isEqualTo(8);
        assertThat(priority).isEqualTo(2);
    }

    @Test
    void anOrdinarySendRefusedAtTheReserveIsToldToWaitUntilATokenAboveTheReserveExists() {
        String key = freshKey();
        grantedOf(8, () -> limiter.tryAcquire(key, 10, 10, 2));

        Decision refused = limiter.tryAcquire(key, 10, 10, 2);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.waitMillis()).isBetween(50L, 100L);
    }

    private static long grantedOf(int attempts, java.util.function.Supplier<Decision> attempt) {
        return IntStream.range(0, attempts).mapToObj(index -> attempt.get()).filter(Decision::allowed).count();
    }

    private static String freshKey() {
        return "test:" + UUID.randomUUID();
    }
}
