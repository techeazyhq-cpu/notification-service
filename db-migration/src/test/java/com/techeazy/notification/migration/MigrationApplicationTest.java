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

package com.techeazy.notification.migration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationApplicationTest {

    private final MigrationApplication app = new MigrationApplication();

    @Test
    void refusesTheDefaultPasswordOutsideTheLocalProfile() {
        MigrationProperties props = new MigrationProperties();
        props.setPassword("notification");

        MockEnvironment environment = new MockEnvironment();

        assertThatThrownBy(() -> app.migrationService(props, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration.password");
    }

    @Test
    void allowsTheDefaultPasswordWithTheLocalProfile() {
        MigrationProperties props = new MigrationProperties();
        props.setPassword("notification");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        assertThatCode(() -> app.migrationService(props, env)).doesNotThrowAnyException();
    }

    @Test
    void allowsARealPasswordOutsideTheLocalProfile() {
        MigrationProperties props = new MigrationProperties();
        props.setPassword("a real production password");

        assertThat(app.migrationService(props, new MockEnvironment())).isNotNull();
    }
}
