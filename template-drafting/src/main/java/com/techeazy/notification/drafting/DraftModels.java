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

import java.util.Optional;

/**
 * Builds the model that writes drafts for the settings in effect, so a new key, model or effort saved in the console
 * is used by the next draft. {@link LlmDraftModels} in the running services.
 */
@FunctionalInterface
public interface DraftModels {

    /** The model for {@code settings}; empty when they leave no way to call one. */
    Optional<DraftModel> forSettings(DraftingSettings settings);
}
