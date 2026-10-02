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
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.error.TraceIdSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Spring Security's refusals, answered with the catalogue's error body (ADR-031): no session is
 * {@link ErrorCode#SIGN_IN_REQUIRED}, unfinished account setup is {@link ErrorCode#ACCOUNT_SETUP_REQUIRED} (so the
 * admin console can send the administrator to their account page, ADR-020), and any other refusal is
 * {@link ErrorCode#FORBIDDEN}.
 */
public class SecurityRefusals implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String SIGN_IN_MESSAGE = "Sign in first; the session is missing, expired or signed out";
    private static final String SETUP_MESSAGE = "Finish setting up your account first; My account lists what is left";
    private static final String FORBIDDEN_MESSAGE = "Your role does not allow this action";

    private final ObjectMapper json = new ObjectMapper();
    private final TraceIdSource traceIds;

    public SecurityRefusals(TraceIdSource traceIds) {
        this.traceIds = traceIds;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException refusal)
            throws IOException {
        write(response, ErrorCode.SIGN_IN_REQUIRED, SIGN_IN_MESSAGE);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
            throws IOException {
        if (setupPending(SecurityContextHolder.getContext().getAuthentication())) {
            write(response, ErrorCode.ACCOUNT_SETUP_REQUIRED, SETUP_MESSAGE);
        } else {
            write(response, ErrorCode.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
    }

    private void write(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.httpStatus().orElseThrow());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                ErrorBody.of(errorCode, message, traceIds.currentTraceId().orElse(null)));
    }

    private static boolean setupPending(Authentication authentication) {
        String accountSetupAuthority = "ROLE_" + BearerTokenFilter.ACCOUNT_SETUP_ROLE;
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> accountSetupAuthority.equals(authority.getAuthority()));
    }
}
