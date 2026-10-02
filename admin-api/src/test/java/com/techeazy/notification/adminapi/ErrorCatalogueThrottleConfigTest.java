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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.error.TraceIdSource;
import com.techeazy.notification.infra.ErrorCatalogueThrottle;
import com.techeazy.notification.port.RateLimiter.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dictionary this API serves without credentials is throttled on the path it is served at. */
class ErrorCatalogueThrottleConfigTest {

    @Test
    void theThrottleGuardsThisApisDictionaryPath() {
        RateLimitService exhausted = mock(RateLimitService.class);
        when(exhausted.checkPublicCatalogue(ErrorCatalogueController.PATH)).thenReturn(new Decision(false, 1_000));
        new ApplicationContextRunner()
                .withBean(RateLimitService.class, () -> exhausted)
                .withBean(ObjectMapper.class)
                .withBean(TraceIdSource.class, () -> Optional::empty)
                .withUserConfiguration(ErrorCatalogueThrottleConfig.class)
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", ErrorCatalogueController.PATH);
                    request.setRequestURI(ErrorCatalogueController.PATH + "/NS-5001");
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    context.getBean(ErrorCatalogueThrottle.class).doFilter(request, response, new MockFilterChain());

                    assertThat(response.getStatus()).isEqualTo(429);
                });
    }
}
