package com.techeazy.notification.domain;

import org.junit.jupiter.api.Test;

import static com.techeazy.notification.domain.RequestStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestStatusTest {

    @Test
    void processingWhileAnyMessageIsInFlight() {
        assertThat(derive(10, 3, 5, 2)).isEqualTo(PROCESSING);
    }

    @Test
    void processingWhenMessagesAreNotAccountedFor() {
        assertThat(derive(10, 0, 4, 1)).isEqualTo(PROCESSING);
    }

    @Test
    void completedWhenAllSent() {
        assertThat(derive(10, 0, 10, 0)).isEqualTo(COMPLETED);
    }

    @Test
    void failedWhenAllFailed() {
        assertThat(derive(3, 0, 0, 3)).isEqualTo(FAILED);
    }

    @Test
    void partiallyFailedWhenMixed() {
        assertThat(derive(10, 0, 7, 3)).isEqualTo(PARTIALLY_FAILED);
    }
}
