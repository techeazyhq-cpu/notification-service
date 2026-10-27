/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.drafting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientDraftingPolicyTest {

    @Test
    void withoutABudgetOfItsOwnAClientHasTheDefault() {
        assertThat(ClientDraftingPolicy.DEFAULT.monthlyTokenBudget(1000)).isEqualTo(1000);
    }

    @Test
    void aBudgetOfItsOwnReplacesTheDefault() {
        assertThat(new ClientDraftingPolicy(true, 5000L).monthlyTokenBudget(1000)).isEqualTo(5000);
    }

    @Test
    void aClientSwitchedOffHasNoBudget() {
        assertThat(new ClientDraftingPolicy(false, 5000L).monthlyTokenBudget(1000)).isZero();
    }

    @Test
    void aNegativeBudgetIsRefused() {
        assertThatThrownBy(() -> new ClientDraftingPolicy(true, -1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
