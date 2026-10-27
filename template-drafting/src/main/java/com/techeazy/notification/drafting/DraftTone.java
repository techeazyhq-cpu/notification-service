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

/** The voice a draft is written in. A fixed list keeps the prompt predictable and the UI a simple choice. */
public enum DraftTone {
    FRIENDLY("warm and friendly, but not overfamiliar"),
    PROFESSIONAL("clear, polite and professional"),
    FORMAL("formal and courteous"),
    REASSURING("calm and reassuring"),
    URGENT("direct and urgent, without alarming the reader");

    private final String description;

    DraftTone(String description) {
        this.description = description;
    }

    /** How the tone is described to the model. */
    public String description() {
        return description;
    }
}
