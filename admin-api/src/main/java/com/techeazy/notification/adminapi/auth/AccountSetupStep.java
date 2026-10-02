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

/**
 * What an administrator still has to do before their session may use anything but their own account (see ADR-020).
 * Which steps apply is set by {@code admin.require-password-change} and {@code admin.require-two-factor}.
 */
public enum AccountSetupStep {
    /** Replace the password the account was created with, by the bootstrap or by another administrator. */
    CHANGE_PASSWORD,
    /** Turn on authenticator-app two-factor authentication. */
    ENABLE_TWO_FACTOR
}
