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
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The adapter against a mocked SDK client: the request it builds and how it reads replies. No API is called. */
class ClaudeDraftModelTest {

    private static final DraftModel.Prompt PROMPT = new DraftModel.Prompt("system rules", "the brief");

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messages = mock(MessageService.class);
    private final DraftingProperties properties = new DraftingProperties();
    private final ClaudeDraftModel model = new ClaudeDraftModel(client, properties);

    @BeforeEach
    void setUp() {
        when(client.messages()).thenReturn(messages);
    }

    @Test
    void theRequestUsesTheConfiguredModelEffortAndAStrictJsonFormat() {
        MessageCreateParams request = model.request(PROMPT);

        assertThat(request.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(request.maxTokens()).isEqualTo(8000);
        assertThat(request.system()).hasValueSatisfying(system -> assertThat(system.asString()).isEqualTo("system rules"));
        OutputConfig output = request.outputConfig().orElseThrow();
        assertThat(output.effort()).contains(OutputConfig.Effort.LOW);
        assertThat(output.format()).isPresent();
        assertThat(request.messages()).singleElement()
                .satisfies(message -> assertThat(message.content().asString()).isEqualTo("the brief"));
    }

    @Test
    void serverSideFallbacksAreRequestedUnlessSwitchedOff() {
        assertThat(model.request(PROMPT)._additionalHeaders().values("anthropic-beta"))
                .containsExactly(ClaudeDraftModel.FALLBACK_BETA);
        assertThat(model.request(PROMPT)._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));

        properties.setFallbacks(false);

        assertThat(model.request(PROMPT)._additionalHeaders().values("anthropic-beta")).isEmpty();
        assertThat(model.request(PROMPT)._additionalBodyProperties()).doesNotContainKey("fallbacks");
    }

    @Test
    void aJsonReplyIsReadAsSubjectAndBodyWithItsTokens() throws Exception {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("end_turn",
                "{\\\"subject\\\":\\\"Welcome\\\",\\\"body\\\":\\\"<p>Hi {{name}}</p>\\\"}"));

        DraftModel.Reply reply = model.complete(PROMPT);

        assertThat(reply.subject()).isEqualTo("Welcome");
        assertThat(reply.body()).isEqualTo("<p>Hi {{name}}</p>");
        assertThat(reply.declined()).isFalse();
        assertThat(reply.inputTokens()).isEqualTo(420);
        assertThat(reply.outputTokens()).isEqualTo(88);
        assertThat(reply.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void aRefusalIsReportedAsDeclined() throws Exception {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("refusal", ""));

        assertThat(model.complete(PROMPT).declined()).isTrue();
    }

    @Test
    void aReplyCutOffAtTheTokenLimitHasNoBody() throws Exception {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("max_tokens", "{\\\"subject\\\":"));

        DraftModel.Reply reply = model.complete(PROMPT);

        assertThat(reply.body()).isNull();
        assertThat(reply.declined()).isFalse();
        assertThat(reply.totalTokens()).isEqualTo(508);
    }

    @Test
    void aReplyThatIsNotJsonHasNoBody() throws Exception {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("end_turn", "Sure! Here it is"));

        assertThat(model.complete(PROMPT).body()).isNull();
    }

    @Test
    void anUnreachableApiIsReportedAsUnavailable() {
        when(messages.create(any(MessageCreateParams.class))).thenThrow(new AnthropicIoException("connection reset"));

        assertThatThrownBy(() -> model.complete(PROMPT)).isInstanceOf(DraftModelUnavailableException.class);
    }

    private static Message message(String stopReason, String text) throws Exception {
        return ObjectMappers.jsonMapper().readValue("""
                {"id": "msg_test", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "stop_reason": "%s", "stop_sequence": null,
                 "content": [{"type": "text", "text": "%s", "citations": null}],
                 "usage": {"input_tokens": 420, "output_tokens": 88}}
                """.formatted(stopReason, text), Message.class);
    }
}
