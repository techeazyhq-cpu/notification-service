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

/**
 * The LLM providers that can draft templates (ADR-044). Anthropic is called through its own API; every other provider
 * through OpenAI's Chat Completions API, which OpenAI, Google Gemini, DeepSeek and Ollama all offer, and which
 * {@link #OPENAI_COMPATIBLE} covers for any other server that speaks it (vLLM, LM Studio, a gateway).
 *
 * <p>Each constant holds what differs between them as data: the protocol, the endpoint used when no base URL is set,
 * whether an API key is needed, how a JSON reply is asked for and what the reply's token ceiling is called.
 */
public enum LlmProvider {

    ANTHROPIC(Protocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com", true, JsonReply.SCHEMA, null),
    OPENAI(Protocol.OPENAI_CHAT_COMPLETIONS, "https://api.openai.com/v1", true, JsonReply.SCHEMA,
            "max_completion_tokens"),
    GEMINI(Protocol.OPENAI_CHAT_COMPLETIONS, "https://generativelanguage.googleapis.com/v1beta/openai", true,
            JsonReply.SCHEMA, "max_tokens"),
    DEEPSEEK(Protocol.OPENAI_CHAT_COMPLETIONS, "https://api.deepseek.com", true, JsonReply.OBJECT, "max_tokens"),
    OLLAMA(Protocol.OPENAI_CHAT_COMPLETIONS, "http://localhost:11434/v1", false, JsonReply.OBJECT, "max_tokens"),
    OPENAI_COMPATIBLE(Protocol.OPENAI_CHAT_COMPLETIONS, null, false, JsonReply.OBJECT, "max_tokens");

    /** The API a provider is called through. */
    public enum Protocol { ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS }

    /** How a reply is held to the draft's JSON shape: a strict schema, or only "a JSON object" where that is all. */
    public enum JsonReply { SCHEMA, OBJECT }

    private final Protocol protocol;
    private final String defaultEndpoint;
    private final boolean apiKeyRequired;
    private final JsonReply jsonReply;
    private final String maxTokensField;

    LlmProvider(Protocol protocol, String defaultEndpoint, boolean apiKeyRequired, JsonReply jsonReply,
                String maxTokensField) {
        this.protocol = protocol;
        this.defaultEndpoint = defaultEndpoint;
        this.apiKeyRequired = apiKeyRequired;
        this.jsonReply = jsonReply;
        this.maxTokensField = maxTokensField;
    }

    public Protocol protocol() { return protocol; }

    /** Where the provider is reached when no base URL is set; null when a base URL must be given. */
    public String defaultEndpoint() { return defaultEndpoint; }

    /** False for local or self-hosted servers, which often run without authentication. */
    public boolean apiKeyRequired() { return apiKeyRequired; }

    public JsonReply jsonReply() { return jsonReply; }

    /** The request field for the reply's token ceiling; OpenAI's reasoning models accept only the newer name. */
    public String maxTokensField() { return maxTokensField; }
}
