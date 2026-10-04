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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFingerprintsTest {

    private final PayloadFingerprints fingerprints = new PayloadFingerprints("data-key");

    @Test
    void theSameFieldsGiveTheSameFingerprint() {
        assertThat(fingerprints.of(List.of("a", "b"))).isEqualTo(fingerprints.of(List.of("a", "b")));
    }

    @Test
    void fieldBoundariesCannotBeShiftedToForgeAMatch() {
        assertThat(fingerprints.of(List.of("ab", "c"))).isNotEqualTo(fingerprints.of(List.of("a", "bc")));
        assertThat(fingerprints.of(List.of("a;", "b"))).isNotEqualTo(fingerprints.of(List.of("a", ";b")));
    }

    @Test
    void aMissingFieldDiffersFromAnEmptyOne() {
        assertThat(fingerprints.of(Arrays.asList("a", null))).isNotEqualTo(fingerprints.of(List.of("a", "")));
    }

    @Test
    void theFingerprintDependsOnTheKey() {
        assertThat(new PayloadFingerprints("other-key").of(List.of("a"))).isNotEqualTo(fingerprints.of(List.of("a")));
    }
}
