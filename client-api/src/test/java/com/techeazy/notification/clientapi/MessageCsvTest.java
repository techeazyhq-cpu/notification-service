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

import com.techeazy.notification.clientapi.Dtos.MessageView;
import com.techeazy.notification.domain.MessageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageCsvTest {

    @Test
    void formulaLookingTextIsNeutralized() {
        assertThat(MessageCsv.neutralize("=HYPERLINK(\"http://evil\")")).startsWith("'=");
        assertThat(MessageCsv.neutralize("+1 error")).isEqualTo("'+1 error");
        assertThat(MessageCsv.neutralize("-2")).isEqualTo("'-2");
        assertThat(MessageCsv.neutralize("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void ordinaryAndEmptyTextIsUntouched() {
        assertThat(MessageCsv.neutralize("HTTP 503 from provider")).isEqualTo("HTTP 503 from provider");
        assertThat(MessageCsv.neutralize(null)).isEmpty();
        assertThat(MessageCsv.neutralize("")).isEmpty();
    }

    @Test
    void rowKeepsPhoneNumbersIntactAndMatchesHeaderWidth() {
        MessageView m = new MessageView(UUID.randomUUID(), "+14155550123", MessageStatus.FAILED, 3,
                "=bad", null, null, Instant.now());

        List<String> row = MessageCsv.row(m);

        assertThat(row).hasSameSizeAs(MessageCsv.HEADER);
        assertThat(row.get(0)).isEqualTo("+14155550123");
        assertThat(row.get(1)).isEqualTo("FAILED");
        assertThat(row.get(3)).isEqualTo("'=bad");
        assertThat(row.get(5)).isEmpty();
    }
}
