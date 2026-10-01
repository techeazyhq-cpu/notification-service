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

import com.techeazy.notification.adminapi.audit.AuditTrailFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A sign-in has no session yet, so the audit trail can only name the username the caller claimed. */
class SignInAuditAttributionTest {

    private final AdminAuthService auth = mock(AdminAuthService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth)).build();

    @Test
    void aRejectedSignInIsAttributedToTheClaimedUsername() throws Exception {
        when(auth.login(eq("mallory"), any(), any())).thenThrow(AuthException.invalidCredentials());

        MockHttpServletRequest request = mvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"password\":\"guess\"}"))
                .andReturn().getRequest();

        assertThat(request.getAttribute(AuditTrailFilter.CLAIMED_ACTOR_ATTRIBUTE)).isEqualTo("mallory");
    }
}
