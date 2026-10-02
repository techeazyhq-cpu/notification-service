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
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rate limiting protects capacity; it must never become the reason the platform stops (see ADR-024). */
class RedisRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, meters, clock);

    @SuppressWarnings("unchecked")
    private void redisAnswers(Object... results) {
        var stubbing = when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()));
        for (Object result : results) {
            stubbing = result instanceof RuntimeException failure
                    ? stubbing.thenThrow(failure)
                    : stubbing.thenReturn(result);
        }
    }

    private double errorsCounted() {
        return meters.get(RedisRateLimiter.ERRORS_METRIC).counter().count();
    }

    @Test
    void aGrantOrADenialFromRedisIsPassedOn() {
        redisAnswers(List.of(1L, 0L), List.of(0L, 250L));

        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(Decision.GRANTED);
        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(new Decision(false, 250));
    }

    @Test
    void whenRedisFailsTheCallIsLetThroughAndTheErrorIsCounted() {
        redisAnswers(new RedisConnectionFailureException("Unable to connect to redis:6379"));

        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(Decision.GRANTED);
        assertThat(errorsCounted()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void afterAFailureRedisIsNotAskedAgainForAWhileSoAnOutageAddsNoTimeoutToEveryCall() {
        redisAnswers(new RedisConnectionFailureException("down"), List.of(0L, 100L));

        limiter.tryAcquire("api:client", 10, 20);
        clock.advance(RedisRateLimiter.SKIP_AFTER_ERROR.minusMillis(1));
        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(Decision.GRANTED);
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any(), any());

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(new Decision(false, 100));
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    void anUnexpectedReplyIsTreatedAsAFailureToo() {
        redisAnswers((Object) null);

        assertThat(limiter.tryAcquire("api:client", 10, 20)).isEqualTo(Decision.GRANTED);
        assertThat(errorsCounted()).isEqualTo(1.0);
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
