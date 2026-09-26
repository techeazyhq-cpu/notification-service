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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores a message's template variables (names, one-time codes, order numbers) encrypted, so a database dump or backup
 * does not expose them. On the way in the whole map is encrypted and stored as {@code {"_enc":"enc:..."}}; on the way
 * out a map in that shape is decrypted and anything else (rows written before encryption existed, or the empty map
 * left by erasure) is returned unchanged, so existing data keeps working and is encrypted the next time it is written.
 */
@Converter
public class EncryptedVariablesConverter implements AttributeConverter<Map<String, String>, Map<String, String>> {

    static final String WRAPPER_KEY = "_enc";
    private static final String PREFIX = "enc:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() { };

    private final FieldEncryptor encryptor;

    public EncryptedVariablesConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public Map<String, String> convertToDatabaseColumn(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return variables;
        }
        try {
            return Map.of(WRAPPER_KEY, PREFIX + encryptor.encrypt(JSON.writeValueAsString(variables)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise template variables", e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(Map<String, String> stored) {
        if (stored == null || stored.size() != 1 || stored.get(WRAPPER_KEY) == null || !stored.get(WRAPPER_KEY).startsWith(PREFIX)) {
            return stored == null ? new HashMap<>() : new HashMap<>(stored);
        }
        try {
            return new HashMap<>(JSON.readValue(encryptor.decrypt(stored.get(WRAPPER_KEY).substring(PREFIX.length())), MAP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read encrypted template variables", e);
        }
    }
}
