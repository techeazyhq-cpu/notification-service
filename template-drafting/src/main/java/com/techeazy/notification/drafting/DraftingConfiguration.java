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

package com.techeazy.notification.drafting;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.util.Optional;

/**
 * Wires template drafting (ADR-041). The service always exists, so the APIs can say whether drafting is on; the
 * Claude client is only built when {@code template-drafting.api-key} is set.
 */
@Configuration
@EnableConfigurationProperties(DraftingProperties.class)
class DraftingConfiguration {

    @Bean
    DraftUsageLedger draftUsageLedger(JdbcClient jdbc) {
        return new JdbcDraftUsageLedger(jdbc);
    }

    @Bean
    TemplateDraftService templateDraftService(DraftingProperties properties, DraftUsageLedger ledger,
                                              RateLimiter rateLimiter, MeterRegistry meters) {
        return new TemplateDraftService(properties, claude(properties), ledger, rateLimiter, Clock.systemUTC(), meters);
    }

    private static Optional<DraftModel> claude(DraftingProperties properties) {
        if (!properties.hasApiKey()) {
            return Optional.empty();
        }
        return Optional.of(new ClaudeDraftModel(AnthropicOkHttpClient.builder()
                .apiKey(properties.getApiKey())
                .timeout(properties.getTimeout())
                .maxRetries(1)
                .build(), properties));
    }
}
