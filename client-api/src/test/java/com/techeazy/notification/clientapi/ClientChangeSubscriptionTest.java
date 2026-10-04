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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.infra.ClientChangeBroadcast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** A change announced by the admin API reaches the client API's cache, and a missing Redis never stops start-up. */
@Testcontainers(disabledWithoutDocker = true)
class ClientChangeSubscriptionTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final ClientAuthFilter auth = mock(ClientAuthFilter.class);
    private LettuceConnectionFactory connections;
    private ClientChangeSubscription subscription;

    @AfterEach
    void tearDown() throws Exception {
        if (subscription != null) subscription.stop();
        if (connections != null) connections.destroy();
    }

    @Test
    void anAnnouncedChangeEmptiesTheCache() throws Exception {
        start(REDIS.getHost(), REDIS.getMappedPort(6379));
        subscription.keepListening();
        assertThat(subscription.isListening()).isTrue();
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();

        new ClientChangeBroadcast(redis).clientChanged(UUID.randomUUID());

        verify(auth, timeout(5_000)).forgetCachedClients();
    }

    @Test
    void withRedisDownListeningIsRetriedLaterInsteadOfFailing() throws Exception {
        start("localhost", 1);

        assertThatCode(subscription::keepListening).doesNotThrowAnyException();
        assertThat(subscription.isListening()).isFalse();
    }

    @Test
    void listeningStartsOnceRedisComesBackAfterAStartWithoutIt() throws Exception {
        int port = REDIS.getMappedPort(6379);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            start(REDIS.getHost(), port);
            subscription.keepListening();
            assertThat(subscription.isListening()).isFalse();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        subscription.keepListening();

        assertThat(subscription.isListening()).isTrue();
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        new ClientChangeBroadcast(redis).clientChanged(UUID.randomUUID());
        verify(auth, timeout(5_000)).forgetCachedClients();
    }

    private void start(String host, int port) {
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connections.afterPropertiesSet();
        connections.start();
        subscription = new ClientChangeSubscription(connections, auth);
    }
}
