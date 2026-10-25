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
package com.techeazy.notification.adminapi;

import com.techeazy.notification.drafting.TemplateExamples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Administrators start shared templates from the example library too (ADR-046). */
class TemplateExamplesControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TemplateExamplesController(TemplateExamples.load()))
                .setControllerAdvice(new AdminErrorHandler(Optional::empty)).build();
    }

    @Test
    void examplesAreListedForTheChannelAndCategoryAskedFor() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/admin/templates/examples")
                .param("channel", "PUSH").param("category", "PROMOTIONAL")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"id\":\"push-flash-sale\"")
                .doesNotContain("\"channel\":\"SMS\"").doesNotContain("\"category\":\"OTP\"");
    }

    @Test
    void anUnknownChannelIsRefused() throws Exception {
        assertThat(mvc.perform(get("/api/admin/templates/examples").param("channel", "FAX")).andReturn()
                .getResponse().getStatus()).isEqualTo(400);
    }
}
