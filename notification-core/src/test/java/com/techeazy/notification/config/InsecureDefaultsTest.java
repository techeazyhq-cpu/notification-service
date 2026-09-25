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

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsecureDefaultsTest {

    @Test
    void rejectsAKnownInsecureValueOutsideTheLocalProfile() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> InsecureDefaults.reject(env, "admin.password", "admin", "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin.password");
    }

    @Test
    void allowsTheSameValueWhenTheLocalProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");

        assertThatCode(() -> InsecureDefaults.reject(env, "admin.password", "admin", "admin")).doesNotThrowAnyException();
    }

    @Test
    void allowsAnyValueThatIsNotOneOfTheKnownDefaults() {
        MockEnvironment env = new MockEnvironment();

        assertThatCode(() -> InsecureDefaults.reject(env, "admin.password", "a real password", "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    void isLocalReflectsTheActiveProfile() {
        MockEnvironment env = new MockEnvironment();
        assertThat(InsecureDefaults.isLocal(env)).isFalse();

        env.setActiveProfiles("local");
        assertThat(InsecureDefaults.isLocal(env)).isTrue();
    }
}
