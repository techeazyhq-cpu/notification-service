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
package com.techeazy.notification.dispatcher;

import com.techeazy.notification.port.NonceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NonceSweepJobTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final NonceStore nonces = mock(NonceStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NonceSweepJob job = new NonceSweepJob(nonces, meters, Clock.fixed(NOW, ZoneOffset.UTC), 100);

    @Test
    void deletesFullBatchesUntilAPartialOneShowsNothingIsLeft() {
        when(nonces.purgeExpired(NOW, 100)).thenReturn(100, 100, 7);

        job.sweep();

        verify(nonces, times(3)).purgeExpired(NOW, 100);
        assertThat(meters.counter("notification.signature.nonces_purged").count()).isEqualTo(207);
    }

    @Test
    void aFailedSweepIsLoggedAndLeftForTheNextInterval() {
        when(nonces.purgeExpired(eq(NOW), anyInt())).thenThrow(new IllegalStateException("database unavailable"));

        job.sweep();

        verify(nonces).purgeExpired(NOW, 100);
    }
}
