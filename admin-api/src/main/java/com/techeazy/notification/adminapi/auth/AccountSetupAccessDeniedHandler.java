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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

import java.io.IOException;
import java.util.Map;

/**
 * Explains a refusal caused by unfinished account setup, so the admin UI can send the administrator to their account
 * page instead of showing a bare 403. Every other refusal is handled as before.
 */
public class AccountSetupAccessDeniedHandler implements AccessDeniedHandler {

    static final String CODE = "ACCOUNT_SETUP_REQUIRED";
    private static final String MESSAGE = "Finish setting up your account first; My account lists what is left";

    private final AccessDeniedHandler otherRefusals = new AccessDeniedHandlerImpl();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
            throws IOException, ServletException {
        if (!setupPending(SecurityContextHolder.getContext().getAuthentication())) {
            otherRefusals.handle(request, response, refusal);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Map.of("code", CODE, "message", MESSAGE));
    }

    private static boolean setupPending(Authentication authentication) {
        String accountSetupAuthority = "ROLE_" + BearerTokenFilter.ACCOUNT_SETUP_ROLE;
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> accountSetupAuthority.equals(authority.getAuthority()));
    }
}
