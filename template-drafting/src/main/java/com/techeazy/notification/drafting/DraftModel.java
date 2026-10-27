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
 * The language model that writes drafts. The service depends on this port rather than on the Anthropic SDK, so its
 * rules, budget and rate limit are tested without calling the API.
 */
public interface DraftModel {

    /** One request to the model: the fixed instructions and the author's brief. */
    record Prompt(String system, String user) {}

    /**
     * What the model returned. {@code body} is null when the reply could not be read as a draft (cut off at the token
     * limit, or not the requested JSON); the tokens are spent either way.
     *
     * @param declined true when the model refused the brief
     * @param model    the model that produced the reply, which can differ from the configured one after a fallback
     */
    record Reply(String subject, String body, boolean declined, long inputTokens, long outputTokens, String model) {

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** @throws DraftModelUnavailableException when the model cannot be reached or rejects the call itself */
    Reply complete(Prompt prompt);
}
