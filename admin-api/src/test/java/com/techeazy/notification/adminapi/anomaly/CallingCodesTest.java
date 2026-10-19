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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CallingCodesTest {

    @ParameterizedTest
    @CsvSource({
            "141, 1",
            "79, 7",
            "447, 44",
            "919, 91",
            "880, 880",
            "882, 882",
            "971, 971",
            "28, unknown",
            "'', unknown"
    })
    void aNumberMapsToTheCodeItStartsWith(String digits, String expected) {
        assertThat(CallingCodes.of(digits)).isEqualTo(expected);
    }

    @Test
    void noCodeIsThePrefixOfAnother() {
        for (String code : CallingCodes.all()) {
            for (String other : CallingCodes.all()) {
                if (!code.equals(other)) {
                    assertThat(other).as("%s starts with %s", other, code).doesNotStartWith(code);
                }
            }
        }
    }

    @Test
    void everyCodeIsOneToThreeDigits() {
        assertThat(CallingCodes.all()).allMatch(code -> code.matches("[1-9]\\d{0,2}"));
    }
}
