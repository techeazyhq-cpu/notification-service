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
import com.techeazy.notification.config.SecretsConfig;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;

/**
 * Wires template drafting (ADR-041). The service always exists, so the APIs can say whether drafting is on. The
 * settings come from the console, or else the environment (ADR-044), and a Claude client is only built once they hold
 * an API key.
 */
@Configuration
@EnableConfigurationProperties(DraftingProperties.class)
class DraftingConfiguration {

    @Bean
    DraftUsageLedger draftUsageLedger(JdbcClient jdbc) {
        return new JdbcDraftUsageLedger(jdbc);
    }

    @Bean
    DraftingSettingsStore draftingSettingsStore(JdbcClient jdbc, DraftingProperties properties,
                                                @Qualifier(SecretsConfig.TEMPLATE_DRAFTING_API_KEY_CIPHER)
                                                AesGcmCipher apiKeyCipher) {
        return new DraftingSettingsStore(jdbc, apiKeyCipher, properties, Clock.systemUTC());
    }

    @Bean
    TemplateDraftService templateDraftService(DraftingProperties properties, DraftingSettingsStore settings,
                                              DraftUsageLedger ledger, RateLimiter rateLimiter, MeterRegistry meters) {
        DraftModels claude = new ClaudeDraftModels(apiKey -> AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(properties.getTimeout())
                .maxRetries(1)
                .build(), properties.isFallbacks());
        return new TemplateDraftService(properties, settings, claude, ledger, rateLimiter, Clock.systemUTC(), meters);
    }
}
