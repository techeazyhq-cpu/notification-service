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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class DeadLetterReprocessorTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final MessageRetry retry = mock(MessageRetry.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final DeadLetterReprocessor reprocessor = new DeadLetterReprocessor(messages, retry, outbox);
    private final DeadLetterFilter everything = new DeadLetterFilter(null, null, null, null, true);

    private static NotificationMessage message() {
        NotificationMessage m = new NotificationMessage();
        m.setId(UUID.randomUUID());
        return m;
    }

    @Test
    void namedMessagesAreRequeuedAndPublishedOnceEachEvenWhenListedTwice() {
        NotificationMessage a = message();
        NotificationMessage b = message();
        when(retry.requeue(a.getId())).thenReturn(a);
        when(retry.requeue(b.getId())).thenReturn(b);
        when(outbox.publishAndMarkQueued(any())).thenReturn(List.of(a.getId(), b.getId()));

        DeadLetterReprocessor.Result result = reprocessor.reprocess(List.of(a.getId(), b.getId(), a.getId()), everything, null);

        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.requeued()).isEqualTo(2);
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.refusedCount()).isZero();
        verify(retry).requeue(a.getId());
        verify(messages, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aRefusalIsReportedWithItsReasonAndDoesNotStopTheOthers() {
        NotificationMessage ok = message();
        NotificationMessage erased = message();
        NotificationMessage broke = message();
        when(retry.requeue(ok.getId())).thenReturn(ok);
        when(retry.requeue(erased.getId())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "The personal data of this message was erased"));
        when(retry.requeue(broke.getId())).thenThrow(new InsufficientCreditException(Money.of("0.10", "USD"), Money.of("5.00", "USD")));
        when(outbox.publishAndMarkQueued(any())).thenReturn(List.of(ok.getId()));

        DeadLetterReprocessor.Result result = reprocessor.reprocess(List.of(erased.getId(), ok.getId(), broke.getId()), everything, null);

        assertThat(result.requeued()).isEqualTo(1);
        assertThat(result.refusedCount()).isEqualTo(2);
        assertThat(result.refused()).extracting(DeadLetterReprocessor.Refusal::id).containsExactly(erased.getId(), broke.getId());
        assertThat(result.refused().get(0).reason()).contains("erased");
        assertThat(result.refused().get(1).reason()).isNotBlank();
    }

    @Test
    void withoutIdsTheOldestMatchingMessagesAreTakenUpToTheLimit() {
        List<NotificationMessage> found = IntStream.range(0, 3).mapToObj(i -> message()).toList();
        when(messages.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(found));
        found.forEach(m -> when(retry.requeue(m.getId())).thenReturn(m));
        when(outbox.publishAndMarkQueued(any())).thenReturn(found.stream().map(NotificationMessage::getId).toList());
        when(messages.count(any(Specification.class))).thenReturn(7L);

        DeadLetterReprocessor.Result result = reprocessor.reprocess(null, everything, 3);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findAll(any(Specification.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
        assertThat(page.getValue().getSort().getOrderFor("updatedAt").isAscending()).isTrue();
        assertThat(result.requeued()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(7);
    }

    @Test
    void theLimitIsClampedAndDefaultsToTwoHundred() {
        when(messages.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        reprocessor.reprocess(null, everything, 50_000);
        reprocessor.reprocess(null, everything, null);
        reprocessor.reprocess(null, everything, 0);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messages, times(3)).findAll(any(Specification.class), page.capture());
        assertThat(page.getAllValues()).extracting(Pageable::getPageSize).containsExactly(1000, 200, 1);
    }

    @Test
    void nothingIsPublishedWhenNothingWasRequeued() {
        when(messages.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        DeadLetterReprocessor.Result result = reprocessor.reprocess(null, everything, null);

        assertThat(result.selected()).isZero();
        verify(outbox, never()).publishAndMarkQueued(any());
    }

    @Test
    void onlyTheFirstFiftyRefusalsAreListedButAllAreCounted() {
        List<UUID> ids = IntStream.range(0, 60).mapToObj(i -> UUID.randomUUID()).toList();
        when(retry.requeue(any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "changed"));

        DeadLetterReprocessor.Result result = reprocessor.reprocess(ids, everything, null);

        assertThat(result.refusedCount()).isEqualTo(60);
        assertThat(result.refused()).hasSize(50);
    }
}
