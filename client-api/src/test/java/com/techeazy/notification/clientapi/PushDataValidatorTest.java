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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PushDataValidatorTest {

    @Test
    void noDataIsAlwaysFine() {
        assertThat(PushDataValidator.check(Channel.SMS, null)).isNull();
        assertThat(PushDataValidator.check(Channel.EMAIL, Map.of())).isNull();
    }

    @Test
    void acceptsSmallPairsOnAPush() {
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("cnsReqId", "0b6f2c1e-4f5a", "screen_2", "x"))).isNull();
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("k", "v".repeat(PushDataValidator.MAX_VALUE_LENGTH)))).isNull();
    }

    @Test
    void dataIsForPushOnly() {
        assertThat(PushDataValidator.check(Channel.SMS, Map.of("orderId", "1"))).contains("PUSH");
    }

    @Test
    void refusesTooManyEntriesOrTooLongValues() {
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i <= PushDataValidator.MAX_ENTRIES; i++) many.put("k" + i, "v");
        assertThat(PushDataValidator.check(Channel.PUSH, many)).contains("at most");
        assertThat(PushDataValidator.check(Channel.PUSH,
                Map.of("k", "v".repeat(PushDataValidator.MAX_VALUE_LENGTH + 1)))).contains("longer than");
    }

    @Test
    void refusesBadOrReservedKeysAndMissingValues() {
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("google.c.a", "1"))).isNotNull();
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("1st", "1"))).isNotNull();
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("", "1"))).isNotNull();
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("k".repeat(PushDataValidator.MAX_KEY_LENGTH + 1), "1"))).isNotNull();
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("messageId", "1"))).contains("reserved");
        assertThat(PushDataValidator.check(Channel.PUSH, Map.of("from", "1"))).contains("reserved");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);
        assertThat(PushDataValidator.check(Channel.PUSH, nullValue)).contains("missing");
    }
}
