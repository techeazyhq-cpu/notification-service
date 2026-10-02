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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring Security's own refusals answer with the catalogue's error body too (ADR-031), never an empty response. */
class SecurityRefusalsTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final ObjectMapper json = new ObjectMapper();
    private final SecurityRefusals refusals = new SecurityRefusals(() -> Optional.of(TRACE_ID));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aMissingOrExpiredSessionAsksToSignIn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        refusals.commence(new MockHttpServletRequest(), response, new InsufficientAuthenticationException("none"));

        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = json.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("SIGN_IN_REQUIRED");
        assertThat(body.get("errorId").asText()).isEqualTo("NS-2010");
        assertThat(body.get("traceId").asText()).isEqualTo(TRACE_ID);
    }

    @Test
    void anActionOutsideTheRoleIsForbiddenWithAnExplanation() throws Exception {
        signIn("ROLE_VIEWER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        refusals.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json.readTree(response.getContentAsByteArray()).get("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unfinishedAccountSetupSaysSoSoTheConsoleCanSendTheAdministratorToTheirAccount() throws Exception {
        signIn("ROLE_" + BearerTokenFilter.ACCOUNT_SETUP_ROLE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        refusals.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json.readTree(response.getContentAsByteArray()).get("code").asText())
                .isEqualTo("ACCOUNT_SETUP_REQUIRED");
    }

    private static void signIn(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
