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

package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationMessageRepository
        extends JpaRepository<NotificationMessage, UUID>, JpaSpecificationExecutor<NotificationMessage> {

    Page<NotificationMessage> findByRequestId(UUID requestId, Pageable pageable);

    Page<NotificationMessage> findByRequestIdAndStatus(UUID requestId, MessageStatus status, Pageable pageable);

    @Query("select m.status, count(m) from NotificationMessage m where m.requestId = :requestId group by m.status")
    List<Object[]> countByStatus(@Param("requestId") UUID requestId);

    /** Status counts for many requests in one round trip: rows of [requestId, status, count]. */
    @Query("select m.requestId, m.status, count(m) from NotificationMessage m where m.requestId in :requestIds "
            + "group by m.requestId, m.status")
    List<Object[]> countByStatusForRequests(@Param("requestIds") Collection<UUID> requestIds);

    /** A client's message counts since a point in time: rows of [channel, status, count]. */
    @Query("select m.channel, m.status, count(m) from NotificationMessage m "
            + "where m.clientId = :clientId and m.createdAt >= :since group by m.channel, m.status")
    List<Object[]> countByChannelAndStatus(@Param("clientId") UUID clientId, @Param("since") Instant since);

    /**
     * Atomic claim: only one worker can move a message into PROCESSING, which makes redelivery
     * and duplicate Pulsar deliveries harmless. Returns 1 if this caller owns the message.
     */
    @Transactional
    @Modifying
    @Query("""
            update NotificationMessage m set m.status = com.techeazy.notification.domain.MessageStatus.PROCESSING,
                   m.attempts = m.attempts + 1, m.updatedAt = :now
            where m.id = :id and m.status in :claimable""")
    int claim(@Param("id") UUID id, @Param("claimable") Collection<MessageStatus> claimable, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            update NotificationMessage m set m.status = com.techeazy.notification.domain.MessageStatus.SENT,
                   m.providerMessageId = :providerId, m.lastError = null, m.sentAt = :now, m.updatedAt = :now
            where m.id = :id""")
    int markSent(@Param("id") UUID id, @Param("providerId") String providerId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update NotificationMessage m set m.status = :status, m.lastError = :error, m.updatedAt = :now where m.id = :id")
    int markFailedOrRetry(@Param("id") UUID id, @Param("status") MessageStatus status,
                          @Param("error") String error, @Param("now") Instant now);

    /**
     * Gives back a claim taken for an attempt that never reached a provider (circuit open): the message returns
     * to QUEUED and the attempt is not counted, so a provider outage cannot exhaust a message's retries.
     */
    @Transactional
    @Modifying
    @Query("""
            update NotificationMessage m set m.status = com.techeazy.notification.domain.MessageStatus.QUEUED,
                   m.attempts = m.attempts - 1, m.updatedAt = :now
            where m.id = :id and m.status = com.techeazy.notification.domain.MessageStatus.PROCESSING and m.attempts > 0""")
    int release(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            update NotificationMessage m set m.status = com.techeazy.notification.domain.MessageStatus.QUEUED,
                   m.updatedAt = :now
            where m.id in :ids and m.status = com.techeazy.notification.domain.MessageStatus.PENDING""")
    int markQueued(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** Admin re-queue of a FAILED message: resets attempts and puts it back in the outbox state. */
    @Transactional
    @Modifying
    @Query("""
            update NotificationMessage m set m.status = com.techeazy.notification.domain.MessageStatus.PENDING,
                   m.attempts = 0, m.updatedAt = :now
            where m.id = :id and m.status = com.techeazy.notification.domain.MessageStatus.FAILED""")
    int requeueFailed(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Sweeper query: rows stuck in a status for longer than the cutoff. SKIP LOCKED lets several
     * sweeper instances run concurrently without publishing the same rows.
     */
    @Query(value = """
            select * from notification_message
            where status = :status and updated_at < :cutoff
            order by updated_at
            limit :batch
            for update skip locked""", nativeQuery = true)
    List<NotificationMessage> lockStale(@Param("status") String status, @Param("cutoff") Instant cutoff,
                                        @Param("batch") int batch);
}
