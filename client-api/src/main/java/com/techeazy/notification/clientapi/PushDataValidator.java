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

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the optional {@code data} of a push: a few small string pairs the client's app reads when the notification is
 * opened (an id to open, say). FCM delivers them in its own {@code data}, next to the message id the service adds.
 */
final class PushDataValidator {
    static final int MAX_ENTRIES = 10;
    static final int MAX_KEY_LENGTH = 40;
    static final int MAX_VALUE_LENGTH = 256;
    /** Names the service or FCM itself uses; FCM also reserves anything starting with "google." or "gcm.". */
    static final Set<String> RESERVED_KEYS = Set.of("messageId", "from", "message_type", "collapse_key", "notification");
    // A letter, then letters, digits and underscores: no dots, so FCM's "google." and "gcm." prefixes cannot be used.
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*+$");

    private PushDataValidator() {}

    /** @return null when valid (or absent), otherwise a human-readable reason */
    static String check(Channel channel, Map<String, String> data) {
        if (data == null || data.isEmpty()) return null;
        if (channel != Channel.PUSH) return "data applies to the PUSH channel only";
        if (data.size() > MAX_ENTRIES) return "data has at most " + MAX_ENTRIES + " entries";
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
                return "data keys are 1-" + MAX_KEY_LENGTH + " letters, digits or underscores, starting with a letter";
            }
            if (RESERVED_KEYS.contains(key)) return "data key '" + key + "' is reserved";
            String value = entry.getValue();
            if (value == null) return "data value for '" + key + "' is missing";
            if (value.length() > MAX_VALUE_LENGTH) {
                return "data value for '" + key + "' is longer than " + MAX_VALUE_LENGTH + " characters";
            }
        }
        return null;
    }
}
