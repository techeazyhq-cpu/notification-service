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

package com.techeazy.notification.application;

import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxSweeperTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final OutboxSweeper sweeper = new OutboxSweeper(messages, outbox, new NotificationProperties(), transactions);

    private static NotificationMessage message(MessageStatus status) {
        NotificationMessage m = new NotificationMessage();
        m.setId(UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    @Test
    void republishesMessagesThatStayedQueuedAndMarksThemQueuedAgain() {
        NotificationMessage lost = message(MessageStatus.QUEUED);
        when(messages.lockStale(eq("QUEUED"), any(), anyInt())).thenReturn(List.of(lost));
        when(outbox.publishOnly(List.of(lost))).thenReturn(List.of(lost.getId()));

        sweeper.sweep();

        verify(messages).releaseForRepublish(eq(List.of(lost.getId())), any());
        verify(messages).markQueued(eq(List.of(lost.getId())), any());
    }

    @Test
    void theRowsAreReleasedAndCommittedBeforeTheBrokerIsCalled() {
        NotificationMessage lost = message(MessageStatus.QUEUED);
        when(messages.lockStale(eq("QUEUED"), any(), anyInt())).thenReturn(List.of(lost));
        when(outbox.publishOnly(List.of(lost))).thenAnswer(call -> {
            assertThat(transactions.open).as("a transaction is open while publishing").isFalse();
            return List.of(lost.getId());
        });

        sweeper.sweep();

        InOrder order = inOrder(messages, outbox);
        order.verify(messages).releaseForRepublish(eq(List.of(lost.getId())), any());
        order.verify(outbox).publishOnly(List.of(lost));
        order.verify(messages).markQueued(eq(List.of(lost.getId())), any());
    }

    /** A retry waits in the broker's retry topic; if the broker loses it, only this sweep brings the message back. */
    @Test
    void republishesARetryWhoseDelayedRedeliveryNeverArrived() {
        NotificationMessage stranded = message(MessageStatus.RETRYING);
        when(messages.lockStale(eq("RETRYING"), any(), anyInt())).thenReturn(List.of(stranded));
        when(outbox.publishOnly(List.of(stranded))).thenReturn(List.of(stranded.getId()));

        sweeper.sweep();

        verify(messages).markQueued(eq(List.of(stranded.getId())), any());
    }

    @Test
    void leavesARetryAloneUntilWellPastTheLongestBackoff() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        sweeper.sweep();

        verify(messages).lockStale(eq("RETRYING"), cutoff.capture(), anyInt());
        long secondsBack = Duration.between(cutoff.getValue(), before).abs().toSeconds();
        assertThat(secondsBack).isBetween(899L, 901L);
    }

    /** The general thresholds are longer than an OTP's validity, so a lost OTP has thresholds of its own. */
    @Test
    void recoversALostOneTimePasswordWithinAMinuteNotAfterItExpired() {
        NotificationMessage lost = message(MessageStatus.QUEUED);
        when(messages.lockStaleOtp(eq("QUEUED"), any(), anyInt())).thenReturn(List.of(lost));
        when(outbox.publishOnly(List.of(lost))).thenReturn(List.of(lost.getId()));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        sweeper.sweep();

        verify(messages).lockStaleOtp(eq("QUEUED"), cutoff.capture(), anyInt());
        assertThat(Duration.between(cutoff.getValue(), before).abs().toSeconds()).isBetween(59L, 61L);
        verify(outbox).publishOnly(List.of(lost));
        verify(messages).markQueued(eq(List.of(lost.getId())), any());
    }

    @Test
    void sweepsOneTimePasswordsInEveryInFlightStatusBeforeAnythingElse() {
        sweeper.sweep();

        InOrder order = inOrder(messages);
        order.verify(messages).lockStaleOtp(eq("PENDING"), any(), anyInt());
        order.verify(messages).lockStaleOtp(eq("PROCESSING"), any(), anyInt());
        order.verify(messages).lockStaleOtp(eq("QUEUED"), any(), anyInt());
        order.verify(messages).lockStaleOtp(eq("RETRYING"), any(), anyInt());
        order.verify(messages).lockStale(eq("PENDING"), any(), anyInt());
    }

    @Test
    void messagesWhosePublishFailsAreLeftPendingForTheNextSweep() {
        NotificationMessage stuck = message(MessageStatus.PROCESSING);
        when(messages.lockStale(eq("PROCESSING"), any(), anyInt())).thenReturn(List.of(stuck));
        when(outbox.publishOnly(List.of(stuck))).thenReturn(List.of());

        sweeper.sweep();

        verify(messages).releaseForRepublish(eq(List.of(stuck.getId())), any());
        verify(messages, never()).markQueued(any(), any());
    }

    @Test
    void eachNonEmptyBatchIsTakenInATransactionOfItsOwn() {
        when(messages.lockStale(eq("PENDING"), any(), anyInt())).thenReturn(List.of(message(MessageStatus.PENDING)));
        when(messages.lockStale(eq("QUEUED"), any(), anyInt())).thenReturn(List.of(message(MessageStatus.QUEUED)));

        sweeper.sweep();

        assertThat(transactions.count).isEqualTo(8);
        verify(messages, never()).releaseForRepublish(eq(List.of()), any());
    }

    /** Runs each callback directly, remembering whether one is running and how many ran. */
    private static final class RecordingTransactions implements TransactionOperations {
        private boolean open;
        private int count;
        private final List<Object> results = new ArrayList<>();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            open = true;
            count++;
            try {
                T result = action.doInTransaction(mock(TransactionStatus.class));
                results.add(result);
                return result;
            } finally {
                open = false;
            }
        }
    }
}
