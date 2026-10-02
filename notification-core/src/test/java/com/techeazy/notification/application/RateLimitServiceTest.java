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
package com.techeazy.notification.application;

import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.domain.RateLimitPolicy;
import com.techeazy.notification.domain.RateLimitScope;
import com.techeazy.notification.persistence.RateLimitPolicyRepository;
import com.techeazy.notification.port.RateLimiter;
import com.techeazy.notification.port.RateLimiter.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One-time passwords may use the tokens the rest of the traffic must leave in reserve (ADR-033). */
class RateLimitServiceTest {

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final RateLimitPolicyRepository policies = mock(RateLimitPolicyRepository.class);
    private final UUID clientId = UUID.randomUUID();
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        when(policies.findByEnabledTrue()).thenReturn(List.of(
                policy(RateLimitScope.CLIENT_CHANNEL, clientId, 50, 100),
                policy(RateLimitScope.GLOBAL_CHANNEL, null, 200, 400)));
        when(limiter.tryAcquire(anyString(), anyDouble(), anyInt(), anyInt())).thenReturn(Decision.GRANTED);
        service = new RateLimitService(limiter, policies, new NotificationProperties());
    }

    @Test
    void ordinaryMessagesLeaveAFifthOfEachBucketInReserve() {
        service.checkDelivery(clientId, Channel.SMS, MessageCategory.TRANSACTIONAL);

        verify(limiter).tryAcquire(eq("client:" + clientId + ":SMS"), eq(50.0), eq(100), eq(20));
        verify(limiter).tryAcquire(eq("global:SMS"), eq(200.0), eq(400), eq(80));
    }

    @Test
    void oneTimePasswordsMayUseTheReserve() {
        service.checkDelivery(clientId, Channel.SMS, MessageCategory.OTP);

        verify(limiter).tryAcquire(eq("client:" + clientId + ":SMS"), eq(50.0), eq(100), eq(0));
        verify(limiter).tryAcquire(eq("global:SMS"), eq(200.0), eq(400), eq(0));
    }

    @Test
    void aReserveNeverSwallowsAWholeSmallBucket() {
        when(policies.findByEnabledTrue()).thenReturn(List.of(policy(RateLimitScope.GLOBAL_CHANNEL, null, 1, 1)));
        RateLimitService smallBuckets = new RateLimitService(limiter, policies, new NotificationProperties());

        smallBuckets.checkDelivery(clientId, Channel.SMS, MessageCategory.PROMOTIONAL);

        verify(limiter).tryAcquire(eq("global:SMS"), eq(1.0), eq(1), eq(0));
    }

    @ParameterizedTest(name = "burst {0} at {1} keeps {2}")
    @CsvSource({
            "1, 0.2, 0",
            "2, 0.2, 1",
            "4, 0.2, 1",
            "5, 0.2, 1",
            "10, 0.2, 2",
            "100, 0.2, 20",
            "3, 0.9, 2",
            "100, 0, 0"
    })
    void smallBucketsStillKeepATokenForOneTimePasswords(int burst, double fraction, int reserve) {
        assertThat(RateLimitService.reserveOf(burst, fraction)).isEqualTo(reserve);
    }

    @Test
    void theReserveShareIsConfigurable() {
        NotificationProperties properties = new NotificationProperties();
        properties.getRateLimit().setPriorityReserveFraction(0.5);
        RateLimitService halfReserved = new RateLimitService(limiter, policies, properties);

        halfReserved.checkDelivery(clientId, Channel.SMS, MessageCategory.TRANSACTIONAL);

        verify(limiter).tryAcquire(eq("global:SMS"), eq(200.0), eq(400), eq(200));
    }

    @Test
    void aRefusalFromTheClientBucketSpendsNoGlobalToken() {
        when(limiter.tryAcquire(eq("client:" + clientId + ":SMS"), anyDouble(), anyInt(), anyInt()))
                .thenReturn(new Decision(false, 100));

        assertThat(service.checkDelivery(clientId, Channel.SMS, MessageCategory.OTP).allowed()).isFalse();

        verify(limiter, org.mockito.Mockito.never()).tryAcquire(eq("global:SMS"), anyDouble(), anyInt(), anyInt());
    }

    private static RateLimitPolicy policy(RateLimitScope scope, UUID clientId, double rate, int burst) {
        RateLimitPolicy policy = new RateLimitPolicy();
        policy.setScope(scope);
        policy.setClientId(clientId);
        policy.setChannel(Channel.SMS);
        policy.setRatePerSecond(BigDecimal.valueOf(rate));
        policy.setBurst(burst);
        policy.setEnabled(true);
        return policy;
    }
}
