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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.RequestKind;
import com.techeazy.notification.domain.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    /** Content: either {@code templateName}, or inline {@code body} (plus {@code subject} for EMAIL). */
    public record SendRequest(
            @NotNull Channel channel,
            @NotNull @Size(min = 1, max = 320) String recipient,
            String templateName,
            @Size(max = 500) String subject,
            String body,
            Map<String, String> variables,
            @Size(max = 120) String clientReference) {}

    public record BulkRecipient(@NotNull @Size(min = 1, max = 320) String recipient, Map<String, String> variables) {}

    public record BulkRequest(
            @NotNull Channel channel,
            String templateName,
            @Size(max = 500) String subject,
            String body,
            @NotEmpty List<@Valid BulkRecipient> recipients,
            @Size(max = 120) String clientReference) {}

    public record SubmitResponse(UUID requestId, RequestKind kind, RequestStatus status, int total,
                                 List<UUID> messageIds, boolean idempotentReplay, Instant createdAt) {}

    public record StatusCounts(long pending, long queued, long processing, long retrying, long sent, long failed) {}

    public record RequestView(UUID requestId, RequestKind kind, Channel channel, RequestStatus status, int total,
                              StatusCounts counts, String clientReference, Instant createdAt) {}

    public record MessageView(UUID messageId, String recipient, MessageStatus status, int attempts, String lastError,
                              String providerMessageId, Instant sentAt, Instant updatedAt) {}

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

    public record MeView(UUID id, String name, Set<Channel> allowedChannels) {}

    /** Name: 2-64 chars of letters, digits, dot, dash, underscore. The channel cannot be changed after creation. */
    public record TemplateInput(
            @NotNull @Size(min = 2, max = 64) String name,
            @NotNull Channel channel,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 10000) String body) {}

    public enum TemplateScope { OWNED, SHARED }

    /** {@code variables} are what a sender must supply per recipient ({{recipient}} is built in and not listed). */
    public record TemplateView(UUID id, String name, Channel channel, String subject, String body, List<String> variables,
                               TemplateScope scope, boolean readOnly, Instant createdAt, Instant updatedAt) {}

    public record PreviewRequest(@Size(max = 500) String subject, @NotBlank @Size(max = 10000) String body,
                                 Map<String, String> variables) {}

    public record PreviewView(String subject, String body, List<String> requiredVariables, List<String> missingVariables) {}

    public record ChannelStatusCount(Channel channel, MessageStatus status, long count) {}

    /** Message counts for the calling client over the last {@code hours}. */
    public record SummaryView(int hours, long requests, List<ChannelStatusCount> counts) {}
}
