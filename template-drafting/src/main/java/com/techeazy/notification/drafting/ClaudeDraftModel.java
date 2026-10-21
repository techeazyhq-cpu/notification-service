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

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks Claude for a draft through the official Anthropic Java SDK. The reply is constrained to a JSON object with a
 * {@code subject} and a {@code body} (structured outputs), so it is parsed rather than scraped from prose. The model,
 * effort and token ceiling come from the {@link DraftingSettings} in effect (ADR-044). With fallbacks on
 * ({@link DraftingProperties#isFallbacks()}) a declined brief is retried by the API on its recommended fallback model.
 */
class ClaudeDraftModel implements DraftModel {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeDraftModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonOutputFormat.Schema DRAFT_SCHEMA = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "subject", Map.of("type", "string"),
                    "body", Map.of("type", "string"))))
            .putAdditionalProperty("required", JsonValue.from(List.of("subject", "body")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build();

    private final AnthropicClient client;
    private final DraftingSettings settings;
    private final boolean fallbacks;

    ClaudeDraftModel(AnthropicClient client, DraftingSettings settings, boolean fallbacks) {
        this.client = client;
        this.settings = settings;
        this.fallbacks = fallbacks;
    }

    @Override
    public Reply complete(Prompt prompt) {
        Message message = send(request(prompt));
        long inputTokens = message.usage().inputTokens();
        long outputTokens = message.usage().outputTokens();
        String servedBy = message.model().asString();
        StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            return new Reply(null, null, true, inputTokens, outputTokens, servedBy);
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            return new Reply(null, null, false, inputTokens, outputTokens, servedBy);
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());
        try {
            JsonNode draft = JSON.readTree(text);
            return new Reply(textOf(draft, "subject"), textOf(draft, "body"), false, inputTokens, outputTokens,
                    servedBy);
        } catch (JsonProcessingException unreadable) {
            return new Reply(null, null, false, inputTokens, outputTokens, servedBy);
        }
    }

    MessageCreateParams request(Prompt prompt) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(settings.maxTokens())
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(settings.effort()))
                        .format(JsonOutputFormat.builder().schema(DRAFT_SCHEMA).build())
                        .build());
        if (fallbacks) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    private Message send(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException rejected) {
            LOG.error("The Anthropic API rejected the drafting API key; replace it on the AI drafting settings page");
            throw new DraftModelUnavailableException("The API key was rejected", rejected);
        } catch (RateLimitException limited) {
            throw new DraftModelUnavailableException("The Anthropic API rate limit was reached", limited);
        } catch (AnthropicServiceException failed) {
            LOG.error("The Anthropic API refused the drafting request with status {}", failed.statusCode());
            throw new DraftModelUnavailableException("The Anthropic API returned " + failed.statusCode(), failed);
        } catch (AnthropicIoException unreachable) {
            throw new DraftModelUnavailableException("The Anthropic API could not be reached", unreachable);
        }
    }

    private static String textOf(JsonNode draft, String field) {
        JsonNode value = draft.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
