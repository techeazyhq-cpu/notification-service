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

package com.techeazy.notification.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Refuses to start a service that is still using one of this project's shipped development defaults (a password,
 * an encryption key) unless the {@code local} Spring profile is active. Without this, a value that exists only so
 * {@code docker compose up} works out of the box on a laptop can end up running in a shared environment unnoticed.
 *
 * <p>{@code local} is opt-in: nothing activates it automatically, so the default posture is fail-fast. `docker
 * compose`'s own services set it, which is what keeps the reference stack working unchanged.
 */
public final class InsecureDefaults {

    private InsecureDefaults() {
    }

    /**
     * @param property human-readable name of the property being checked, for the error message
     * @param actualValue the value the property currently resolves to
     * @param insecureValues every value this project ships as a development default for this property
     * @throws IllegalStateException if {@code actualValue} matches one of {@code insecureValues} and the {@code local} profile is not active
     */
    public static void reject(Environment env, String property, String actualValue, String... insecureValues) {
        if (isLocal(env)) {
            return;
        }
        for (String insecure : insecureValues) {
            if (insecure.equals(actualValue)) {
                throw new IllegalStateException(property + " is still set to a value this project ships as a development "
                        + "default (\"" + insecure + "\"). Set a real value before running outside local development, or "
                        + "start with the 'local' Spring profile if this really is local development.");
            }
        }
    }

    public static boolean isLocal(Environment env) {
        return Arrays.asList(env.getActiveProfiles()).contains("local");
    }
}
