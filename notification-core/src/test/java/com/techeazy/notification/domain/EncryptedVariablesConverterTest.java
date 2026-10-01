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

package com.techeazy.notification.domain;

import com.techeazy.notification.infra.AesGcmCipher;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedVariablesConverterTest {

    private final EncryptedVariablesConverter converter = new EncryptedVariablesConverter(new AesGcmCipher("test-key"));

    @Test
    void storesAnEncryptedEnvelopeThatDoesNotContainThePlainValues() {
        Map<String, String> stored = converter.convertToDatabaseColumn(Map.of("code", "481516", "name", "Ann"));

        assertThat(stored).containsOnlyKeys("_enc");
        assertThat(stored.get("_enc")).startsWith("enc:").doesNotContain("481516").doesNotContain("Ann");
    }

    @Test
    void readsBackWhatWasStored() {
        Map<String, String> variables = Map.of("code", "481516", "name", "Ann");

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(variables))).isEqualTo(variables);
    }

    @Test
    void returnsRowsWrittenBeforeEncryptionExistedUnchanged() {
        Map<String, String> legacy = Map.of("code", "1111");

        assertThat(converter.convertToEntityAttribute(legacy)).isEqualTo(legacy);
    }

    @Test
    void leavesTheEmptyMapLeftByErasureAlone() {
        assertThat(converter.convertToDatabaseColumn(Map.of())).isEmpty();
        assertThat(converter.convertToEntityAttribute(Map.of())).isEmpty();
    }

    @Test
    void refusesToReadAnEnvelopeThatWasEncryptedWithAnotherKey() {
        Map<String, String> stored = new EncryptedVariablesConverter(new AesGcmCipher("other-key")).convertToDatabaseColumn(Map.of("a", "b"));

        assertThatThrownBy(() -> converter.convertToEntityAttribute(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsNullAsEmpty() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
