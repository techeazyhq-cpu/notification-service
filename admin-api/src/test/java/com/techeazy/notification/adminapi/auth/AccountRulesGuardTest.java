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

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRulesGuardTest {

    private static MockEnvironment environmentWithProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void bothRulesOnStartsAnywhere() {
        assertThatCode(() -> AuthConfiguration.rejectRelaxedAccountRules(environmentWithProfiles(), true, true))
                .doesNotThrowAnyException();
    }

    @Test
    void relaxingEitherRuleOutsideLocalDevelopmentRefusesToStart() {
        var noProfiles = environmentWithProfiles();
        assertThatThrownBy(() -> AuthConfiguration.rejectRelaxedAccountRules(noProfiles, false, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admin.require-password-change");
        var production = environmentWithProfiles("prod");
        assertThatThrownBy(() -> AuthConfiguration.rejectRelaxedAccountRules(production, true, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admin.require-two-factor");
    }

    @Test
    void localDevelopmentMayRelaxBoth() {
        assertThatCode(() -> AuthConfiguration.rejectRelaxedAccountRules(environmentWithProfiles("local"), false,
                false))
                .doesNotThrowAnyException();
    }
}
