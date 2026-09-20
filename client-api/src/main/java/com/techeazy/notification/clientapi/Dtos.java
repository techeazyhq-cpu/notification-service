package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.RequestKind;
import com.techeazy.notification.domain.RequestStatus;
import jakarta.validation.Valid;
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

    public record ChannelStatusCount(Channel channel, MessageStatus status, long count) {}

    /** Message counts for the calling client over the last {@code hours}. */
    public record SummaryView(int hours, long requests, List<ChannelStatusCount> counts) {}
}
