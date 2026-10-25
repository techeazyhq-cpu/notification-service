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

import java.util.Map;
import java.util.UUID;

/**
 * Each client's AI drafting policy (ADR-045); a client without a saved one has {@link ClientDraftingPolicy#DEFAULT}.
 */
public interface ClientDraftingPolicies {

    ClientDraftingPolicy of(UUID clientId);

    /** Every saved policy, by client. */
    Map<UUID, ClientDraftingPolicy> all();

    /** Saves {@code policy} for the client as {@code administrator}'s, replacing any earlier one. */
    void save(UUID clientId, ClientDraftingPolicy policy, String administrator);
}
