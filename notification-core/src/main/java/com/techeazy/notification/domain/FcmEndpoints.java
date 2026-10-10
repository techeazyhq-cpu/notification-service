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

/**
 * Where an {@link ProviderType#FCM} provider connects unless its settings say otherwise: the FCM HTTP v1 API and
 * Google's OAuth 2.0 token endpoint. Both are overridable (settings {@code url} and {@code tokenUrl}) so that a test
 * double or a regional endpoint can be used, and both go through the provider destination policy (ADR-022).
 */
public final class FcmEndpoints {

    public static final String DEFAULT_URL = "https://fcm.googleapis.com";
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String TOKEN_URL_SETTING = "tokenUrl";

    private FcmEndpoints() {
    }
}
