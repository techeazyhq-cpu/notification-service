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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter for OpenAI's Chat Completions API, which OpenAI, Gemini, DeepSeek, Ollama and many others offer, against
 * a local HTTP server standing in for the provider: the request it sends and how it reads replies.
 */
class OpenAiCompatibleDraftModelTest {

    private static final DraftModel.Prompt PROMPT = new DraftModel.Prompt("system rules", "the brief");
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private int replyStatus = 200;
    private String replyBody;
    private JsonNode receivedBody;
    private String receivedAuthorization;
    private String receivedPath;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath = exchange.getRequestURI().getPath();
            receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedBody = JSON.readTree(exchange.getRequestBody());
            byte[] body = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
    }

    private OpenAiCompatibleDraftModel model(LlmProvider provider, String apiKey) {
        DraftingSettings settings = new DraftingSettings(true, provider, baseUrl, apiKey, "test-model", "low", 4000,
                1, 1, 5);
        return new OpenAiCompatibleDraftModel(HttpClient.newHttpClient(), settings, Duration.ofSeconds(5));
    }

    private void replyWith(String finishReason, String content) throws IOException {
        ObjectNode reply = JSON.createObjectNode().put("model", "test-model-2026");
        ObjectNode choice = reply.putArray("choices").addObject().put("finish_reason", finishReason);
        choice.putObject("message").put("role", "assistant").put("content", content);
        reply.putObject("usage").put("prompt_tokens", 310).put("completion_tokens", 95);
        replyBody = JSON.writeValueAsString(reply);
    }

    @Test
    void theRequestCarriesTheModelPromptTokenCeilingAndKey() throws Exception {
        replyWith("stop", "{\"subject\":\"\",\"body\":\"Hi {{firstName}}\"}");

        model(LlmProvider.DEEPSEEK, "sk-test-key").complete(PROMPT);

        assertThat(receivedPath).isEqualTo("/v1/chat/completions");
        assertThat(receivedAuthorization).isEqualTo("Bearer sk-test-key");
        assertThat(receivedBody.get("model").asText()).isEqualTo("test-model");
        assertThat(receivedBody.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(receivedBody.get("messages").get(0).get("content").asText()).isEqualTo("system rules");
        assertThat(receivedBody.get("messages").get(1).get("content").asText()).isEqualTo("the brief");
        assertThat(receivedBody.get("max_tokens").asLong()).isEqualTo(4000);
        assertThat(receivedBody.get("response_format").get("type").asText()).isEqualTo("json_object");
    }

    @Test
    void openAiGetsAStrictJsonSchemaAndItsNewTokenCeilingField() throws Exception {
        replyWith("stop", "{\"subject\":\"\",\"body\":\"Hi\"}");

        model(LlmProvider.OPENAI, "sk-test-key").complete(PROMPT);

        assertThat(receivedBody.has("max_tokens")).isFalse();
        assertThat(receivedBody.get("max_completion_tokens").asLong()).isEqualTo(4000);
        JsonNode format = receivedBody.get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
        assertThat(format.get("json_schema").get("schema").get("required"))
                .extracting(JsonNode::asText).containsExactly("subject", "body");
    }

    @Test
    void withoutAKeyNoAuthorizationIsSent() throws Exception {
        replyWith("stop", "{\"subject\":\"\",\"body\":\"Hi\"}");

        model(LlmProvider.OLLAMA, null).complete(PROMPT);

        assertThat(receivedAuthorization).isNull();
    }

    @Test
    void aJsonReplyIsReadAsSubjectAndBodyWithItsTokensAndModel() throws Exception {
        replyWith("stop", "{\"subject\":\"Welcome\",\"body\":\"<p>Hi {{name}}</p>\"}");

        DraftModel.Reply reply = model(LlmProvider.OPENAI, "sk-test-key").complete(PROMPT);

        assertThat(reply.subject()).isEqualTo("Welcome");
        assertThat(reply.body()).isEqualTo("<p>Hi {{name}}</p>");
        assertThat(reply.declined()).isFalse();
        assertThat(reply.inputTokens()).isEqualTo(310);
        assertThat(reply.outputTokens()).isEqualTo(95);
        assertThat(reply.model()).isEqualTo("test-model-2026");
    }

    @Test
    void reasoningAndMarkdownFencesAroundTheJsonAreIgnored() throws Exception {
        replyWith("stop", "<think>The user wants an SMS.</think>\n"
                + "```json\n{\"subject\":\"\",\"body\":\"Hi {{a}}\"}\n```");

        assertThat(model(LlmProvider.OLLAMA, null).complete(PROMPT).body()).isEqualTo("Hi {{a}}");
    }

    @Test
    void aReplyCutOffAtTheTokenLimitHasNoBodyButCountsItsTokens() throws Exception {
        replyWith("length", "{\"subject\":");

        DraftModel.Reply reply = model(LlmProvider.GEMINI, "key").complete(PROMPT);

        assertThat(reply.body()).isNull();
        assertThat(reply.declined()).isFalse();
        assertThat(reply.totalTokens()).isEqualTo(405);
    }

    @Test
    void aContentFilterStopIsReportedAsDeclined() throws Exception {
        replyWith("content_filter", "");

        assertThat(model(LlmProvider.OPENAI, "key").complete(PROMPT).declined()).isTrue();
    }

    @Test
    void aReplyThatIsNotJsonHasNoBody() throws Exception {
        replyWith("stop", "Sure! Here it is");

        assertThat(model(LlmProvider.OPENAI, "key").complete(PROMPT).body()).isNull();
    }

    @Test
    void aRejectedKeyIsReportedAsUnavailable() {
        replyStatus = 401;
        replyBody = "{\"error\":{\"message\":\"Incorrect API key\"}}";

        assertThatThrownBy(() -> model(LlmProvider.OPENAI, "bad").complete(PROMPT))
                .isInstanceOf(DraftModelUnavailableException.class).hasMessageContaining("key was rejected");
    }

    @Test
    void anUnreachableProviderIsReportedAsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> model(LlmProvider.OLLAMA, null).complete(PROMPT))
                .isInstanceOf(DraftModelUnavailableException.class).hasMessageContaining("could not be reached");
    }
}
