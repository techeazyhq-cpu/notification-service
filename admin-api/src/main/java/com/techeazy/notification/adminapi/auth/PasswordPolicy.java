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

package com.techeazy.notification.adminapi.auth;

/** Length-based password rules: long enough to resist guessing, no composition tricks, never the user name. */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;
    private static final int MIN_DISTINCT_CHARACTERS = 5;

    private PasswordPolicy() {
    }

    /** Returns why the password is unacceptable, or {@code null} when it is fine. */
    static String violation(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters long";
        }
        if (password.length() > MAX_LENGTH) {
            return "The password must be at most " + MAX_LENGTH + " characters long";
        }
        if (password.toLowerCase().contains(username.toLowerCase())) {
            return "The password must not contain the user name";
        }
        if (password.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            return "The password is too repetitive";
        }
        return null;
    }
}
