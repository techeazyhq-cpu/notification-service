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

package com.techeazy.notification.drafting;

import java.util.UUID;

/**
 * Who asked for a draft. An administrator drafts shared templates and spends only the platform budget; a client
 * drafts its own and spends its own monthly budget as well.
 *
 * @param name     the administrator's username, or the client's name
 * @param clientId the client, or null for an administrator
 */
public record DraftActor(String name, UUID clientId) {

    public static DraftActor administrator(String username) {
        return new DraftActor(username, null);
    }

    public static DraftActor client(UUID clientId, String name) {
        return new DraftActor(name, clientId);
    }

    public boolean isClient() {
        return clientId != null;
    }

    /** The rate-limit bucket: one per administrator and one per client, whoever in the client is calling. */
    String rateLimitKey() {
        return isClient() ? "draft:client:" + clientId : "draft:admin:" + name;
    }
}
