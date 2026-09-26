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

import java.util.List;

/**
 * What an administrator may do. Each role includes everything the ones below it can do: {@code ADMIN} can do
 * everything {@code OPERATOR} can, which includes everything {@code VIEWER} can. See ADR-015 for which endpoints
 * need which role.
 */
public enum AdminRole {
    VIEWER, OPERATOR, ADMIN;

    /** Spring Security role names (without the {@code ROLE_} prefix) this role should be granted, including implied ones. */
    List<String> impliedRoleNames() {
        return switch (this) {
            case VIEWER -> List.of("VIEWER");
            case OPERATOR -> List.of("VIEWER", "OPERATOR");
            case ADMIN -> List.of("VIEWER", "OPERATOR", "ADMIN");
        };
    }
}
