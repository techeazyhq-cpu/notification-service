/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.drafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks for a draft through OpenAI's Chat Completions API ({@code POST <endpoint>/chat/completions}), which OpenAI,
 * Google Gemini, DeepSeek, Ollama and many self-hosted servers offer (ADR-044). The reply is held to a JSON object with
 * a {@code subject} and a {@code body}: by a strict JSON schema where the provider supports one, otherwise by asking
 * for a JSON object. Local models often wrap that JSON in reasoning or a Markdown fence, which is removed before it is
 * read.
 */
class OpenAiCompatibleDraftModel implements DraftModel {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleDraftModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REASONING = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?s)^```(?:json)?\\s*(.*?)\\s*```$");
    private static final String FINISHED_AT_TOKEN_LIMIT = "length";
    private static final String STOPPED_BY_CONTENT_FILTER = "content_filter";
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int TOO_MANY_REQUESTS = 429;

    private final HttpClient http;
    private final DraftingSettings settings;
    private final Duration timeout;

    OpenAiCompatibleDraftModel(HttpClient http, DraftingSettings settings, Duration timeout) {
        this.http = http;
        this.settings = settings;
        this.timeout = timeout;
    }

    @Override
    public Reply complete(Prompt prompt) {
        JsonNode reply = readBody(send(request(prompt)));
        JsonNode usage = reply.path("usage");
        long inputTokens = usage.path("prompt_tokens").asLong(0);
        long outputTokens = usage.path("completion_tokens").asLong(0);
        String servedBy = reply.path("model").asText(settings.model());
        JsonNode choice = reply.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("");
        if (STOPPED_BY_CONTENT_FILTER.equals(finishReason) || !message.path("refusal").asText("").isBlank()) {
            return new Reply(null, null, true, inputTokens, outputTokens, servedBy);
        }
        if (FINISHED_AT_TOKEN_LIMIT.equals(finishReason)) {
            return new Reply(null, null, false, inputTokens, outputTokens, servedBy);
        }
        try {
            JsonNode draft = JSON.readTree(jsonOf(message.path("content").asText("")));
            return new Reply(textOf(draft, "subject"), textOf(draft, "body"), false, inputTokens, outputTokens,
                    servedBy);
        } catch (JsonProcessingException unreadable) {
            return new Reply(null, null, false, inputTokens, outputTokens, servedBy);
        }
    }

    private HttpRequest request(Prompt prompt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(settings.endpoint() + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(prompt)));
        if (settings.hasApiKey()) {
            builder.header("Authorization", "Bearer " + settings.apiKey());
        }
        return builder.build();
    }

    private String body(Prompt prompt) {
        ObjectNode body = JSON.createObjectNode().put("model", settings.model());
        body.putArray("messages")
                .add(JSON.createObjectNode().put("role", "system").put("content", prompt.system()))
                .add(JSON.createObjectNode().put("role", "user").put("content", prompt.user()));
        body.put(settings.provider().maxTokensField(), settings.maxTokens());
        body.set("response_format", responseFormat());
        return body.toString();
    }

    private ObjectNode responseFormat() {
        if (settings.provider().jsonReply() == LlmProvider.JsonReply.OBJECT) {
            return JSON.createObjectNode().put("type", "json_object");
        }
        ObjectNode format = JSON.createObjectNode().put("type", "json_schema");
        ObjectNode schema = format.putObject("json_schema").put("name", "template_draft").put("strict", true)
                .putObject("schema").put("type", "object").put("additionalProperties", false);
        schema.putObject("properties")
                .<ObjectNode>set("subject", JSON.createObjectNode().put("type", "string"))
                .set("body", JSON.createObjectNode().put("type", "string"));
        schema.putArray("required").add("subject").add("body");
        return format;
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new DraftModelUnavailableException(settings.provider() + " could not be reached at "
                    + settings.endpoint(), unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DraftModelUnavailableException("The drafting request was interrupted", interrupted);
        }
        int status = response.statusCode();
        if (status == UNAUTHORIZED || status == FORBIDDEN) {
            LOG.error("{} rejected the drafting API key; replace it on the AI drafting settings page",
                    settings.provider());
            throw new DraftModelUnavailableException("The API key was rejected", null);
        }
        if (status == TOO_MANY_REQUESTS) {
            throw new DraftModelUnavailableException(settings.provider() + "'s rate limit was reached", null);
        }
        if (status / 100 != 2) {
            LOG.error("{} refused the drafting request with status {}", settings.provider(), status);
            throw new DraftModelUnavailableException(settings.provider() + " returned " + status, null);
        }
        return response;
    }

    private JsonNode readBody(HttpResponse<String> response) {
        JsonNode reply;
        try {
            reply = JSON.readTree(response.body());
        } catch (JsonProcessingException unreadable) {
            throw new DraftModelUnavailableException(settings.provider() + " returned a reply that is not JSON",
                    unreadable);
        }
        if (reply.path("choices").isEmpty()) {
            String error = reply.path("error").path("message").asText("no choices in the reply");
            LOG.error("{} answered the drafting request without a draft: {}", settings.provider(), error);
            throw new DraftModelUnavailableException(settings.provider() + " returned no draft: " + error, null);
        }
        return reply;
    }

    private static String jsonOf(String content) {
        String withoutReasoning = REASONING.matcher(content).replaceAll("").strip();
        Matcher fenced = MARKDOWN_FENCE.matcher(withoutReasoning);
        return fenced.matches() ? fenced.group(1) : withoutReasoning;
    }

    private static String textOf(JsonNode draft, String field) {
        JsonNode value = draft.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
