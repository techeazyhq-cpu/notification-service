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

import com.techeazy.notification.adminapi.auth.AdminAuthService;
import com.techeazy.notification.adminapi.auth.AdminRole;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Every role against the endpoints that mark the boundary between them; a 404 means security let the request through. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityRulesTest.Config.class)
class SecurityRulesTest {

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Config {
        @Bean
        AdminAuthService adminAuthService() {
            return mock(AdminAuthService.class);
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AdminAuthService auth;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        for (AdminRole role : AdminRole.values()) {
            when(auth.authenticate(role.name())).thenReturn(Optional.of(new AdminAuthService.AuthenticatedSession("user", "hash", role)));
        }
    }

    private int status(AdminRole role, HttpMethod method, String path) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path);
        if (role != null) {
            builder.header("Authorization", "Bearer " + role.name());
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void anonymousCallersAreRefusedEverywhereExceptLoginAndHealth() throws Exception {
        assertThat(status(null, HttpMethod.GET, "/api/admin/clients")).isEqualTo(401);
        assertThat(status(null, HttpMethod.POST, "/api/admin/clients")).isEqualTo(401);
        assertThat(status(null, HttpMethod.GET, "/api/admin/auth/me")).isEqualTo(401);
        assertThat(status(null, HttpMethod.POST, "/api/admin/auth/login")).isEqualTo(404);
    }

    @Test
    void aViewerMayReadEverythingButChangeNothing() throws Exception {
        assertThat(status(AdminRole.VIEWER, HttpMethod.GET, "/api/admin/clients")).isEqualTo(404);
        assertThat(status(AdminRole.VIEWER, HttpMethod.GET, "/api/admin/auth/me")).isEqualTo(404);
        assertThat(status(AdminRole.VIEWER, HttpMethod.POST, "/api/admin/clients")).isEqualTo(403);
        assertThat(status(AdminRole.VIEWER, HttpMethod.POST, "/api/admin/dead-letters/reprocess")).isEqualTo(403);
        assertThat(status(AdminRole.VIEWER, HttpMethod.GET, "/api/admin/administrators")).isEqualTo(403);
    }

    @Test
    void anOperatorMayRecoverFailuresButNotChangeConfiguration() throws Exception {
        assertThat(status(AdminRole.OPERATOR, HttpMethod.POST, "/api/admin/dead-letters/reprocess")).isEqualTo(404);
        assertThat(status(AdminRole.OPERATOR, HttpMethod.POST, "/api/admin/messages/1/retry")).isEqualTo(404);
        assertThat(status(AdminRole.OPERATOR, HttpMethod.POST, "/api/admin/privacy/erasure")).isEqualTo(404);
        assertThat(status(AdminRole.OPERATOR, HttpMethod.POST, "/api/admin/billing/accounts/1/credit")).isEqualTo(404);
        assertThat(status(AdminRole.OPERATOR, HttpMethod.PUT, "/api/admin/providers/1")).isEqualTo(403);
        assertThat(status(AdminRole.OPERATOR, HttpMethod.GET, "/api/admin/administrators")).isEqualTo(403);
    }

    @Test
    void anAdminMayDoEverything() throws Exception {
        assertThat(status(AdminRole.ADMIN, HttpMethod.POST, "/api/admin/clients")).isEqualTo(404);
        assertThat(status(AdminRole.ADMIN, HttpMethod.DELETE, "/api/admin/providers/1")).isEqualTo(404);
        assertThat(status(AdminRole.ADMIN, HttpMethod.GET, "/api/admin/administrators")).isEqualTo(404);
    }

    @Test
    void anythingOutsideTheApiIsDenied() throws Exception {
        assertThat(status(AdminRole.ADMIN, HttpMethod.GET, "/somewhere-else")).isEqualTo(403);
    }
}
