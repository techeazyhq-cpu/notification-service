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
package com.techeazy.notification.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.port.RateLimiter.Decision;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The error dictionary is served without credentials, so it has a shared limit of its own (ADR-031). */
class ErrorCatalogueThrottleTest {

    private static final String PATH = "/v1/errors";

    private final RateLimitService rateLimits = mock(RateLimitService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ErrorCatalogueThrottle throttle =
            new ErrorCatalogueThrottle(rateLimits, mapper, () -> Optional.of("trace-1"), PATH);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void anAllowedReadIsServedAndMarkedCacheable() throws Exception {
        when(rateLimits.checkPublicCatalogue(PATH)).thenReturn(Decision.GRANTED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        throttle.doFilter(get(PATH + "/NS-5001"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=3600");
    }

    @Test
    void aReadOverTheLimitIsRefusedWithTheCatalogueErrorAndWhenToComeBack() throws Exception {
        when(rateLimits.checkPublicCatalogue(PATH)).thenReturn(new Decision(false, 1_500));
        MockHttpServletResponse response = new MockHttpServletResponse();

        throttle.doFilter(get(PATH), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("docs").asText()).isEqualTo(PATH + "/NS-5001");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-1");
    }

    @Test
    void otherPathsAreNotThrottledHere() throws Exception {
        throttle.doFilter(get("/v1/notifications"), new MockHttpServletResponse(), chain);
        throttle.doFilter(get("/v1/errorsx"), new MockHttpServletResponse(), chain);

        verifyNoInteractions(rateLimits);
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
