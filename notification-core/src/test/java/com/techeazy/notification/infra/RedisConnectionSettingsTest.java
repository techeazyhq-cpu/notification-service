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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Helm chart configures Redis authentication and TLS for managed caches (Azure Managed Redis, Memorystore) only
 * through environment variables (ADR-029). These tests pin that exactly those variable names reach the Redis
 * connection, so a renamed property cannot silently leave a cache unauthenticated or untrusted.
 */
class RedisConnectionSettingsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, RedisAutoConfiguration.class));

    private static ApplicationContextRunner withEnvironment(
            ApplicationContextRunner runner, Map<String, Object> environmentVariables) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("chart-systemEnvironment", environmentVariables)));
    }

    @Test
    void theChartsVariablesGiveRedisThePasswordAndTlsTrustingOnlyThePrivateCa() {
        withEnvironment(contextRunner, Map.of(
                "SPRING_DATA_REDIS_PASSWORD", "redis-secret",
                "SPRING_DATA_REDIS_SSL_ENABLED", "true",
                "SPRING_DATA_REDIS_SSL_BUNDLE", "redis",
                "SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE", "classpath:redis/test-ca.pem"))
                .run(context -> {
                    LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(connectionFactory.getPassword()).isEqualTo("redis-secret");
                    assertThat(connectionFactory.isUseSsl()).isTrue();

                    var trustStore = context.getBean(SslBundles.class).getBundle("redis").getStores().getTrustStore();
                    assertThat(trustStore.size()).isEqualTo(1);
                });
    }

    @Test
    void withoutThoseVariablesRedisStaysAnonymousAndPlain() {
        contextRunner.run(context -> {
            LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
            assertThat(connectionFactory.getPassword()).isNull();
            assertThat(connectionFactory.isUseSsl()).isFalse();
        });
    }
}
