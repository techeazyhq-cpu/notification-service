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

package com.techeazy.notification.adminapi.anomaly;

import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnomaliesControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:30:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Authentication ALICE = new TestingAuthenticationToken("alice", null, "ROLE_OPERATOR");

    private final AnomalyRepository repository = mock(AnomalyRepository.class);
    private final AnomaliesController controller = new AnomaliesController(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aDecisionIsRecordedAsTheSignedInAdministratorWithTheNoteTrimmed() {
        AnomalyView decided = view(AnomalyStatus.CONFIRMED);
        when(repository.resolve(ID, AnomalyStatus.CONFIRMED, "alice", "pumping", NOW)).thenReturn(Optional.of(decided));

        AnomalyView result = controller.resolve(ID, new AnomaliesController.Resolution(AnomalyStatus.CONFIRMED, "  pumping \n"),
                ALICE);

        assertThat(result).isEqualTo(decided);
    }

    @Test
    void aBlankNoteIsStoredAsNone() {
        when(repository.resolve(ID, AnomalyStatus.FALSE_ALARM, "alice", null, NOW))
                .thenReturn(Optional.of(view(AnomalyStatus.FALSE_ALARM)));

        controller.resolve(ID, new AnomaliesController.Resolution(AnomalyStatus.FALSE_ALARM, "   "), ALICE);

        verify(repository).resolve(ID, AnomalyStatus.FALSE_ALARM, "alice", null, NOW);
    }

    @Test
    void anAnomalyCannotBeReopenedOrLeftWithoutAStatus() {
        assertThatThrownBy(() -> controller.resolve(ID, new AnomaliesController.Resolution(AnomalyStatus.OPEN, null), ALICE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> controller.resolve(ID, new AnomaliesController.Resolution(null, null), ALICE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(repository);
    }

    @Test
    void aNoteLongerThanFiveHundredCharactersIsRefused() {
        AnomaliesController.Resolution tooLong = new AnomaliesController.Resolution(AnomalyStatus.CONFIRMED, "x".repeat(501));

        assertThatThrownBy(() -> controller.resolve(ID, tooLong, ALICE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    void anUnknownAnomalyIsNotFound() {
        when(repository.find(any())).thenReturn(Optional.empty());
        when(repository.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(ID)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> controller.resolve(ID, new AnomaliesController.Resolution(AnomalyStatus.CONFIRMED, null), ALICE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void searchPassesTheFiltersAndKeepsThePageSizeInBounds() {
        UUID client = UUID.randomUUID();
        AnomalyRepository.Query query = new AnomalyRepository.Query(AnomalyStatus.OPEN, client, "SMS", Severity.HIGH);
        AnomalyRepository.Page page = new AnomalyRepository.Page(List.of(), 0, 200, 0);
        when(repository.search(query, 0, 200)).thenReturn(page);

        assertThat(controller.search(AnomalyStatus.OPEN, client, Channel.SMS, Severity.HIGH, -3, 10_000)).isEqualTo(page);
    }

    private static AnomalyView view(AnomalyStatus status) {
        return new AnomalyView(ID, UUID.randomUUID(), "Acme", "SMS", NOW, Feature.MESSAGES, DetectionMethod.BASELINE, 100,
                900, 9.5, Severity.HIGH, null, status, NOW, "alice", NOW, null);
    }
}
