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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Callers get the trace id back, so a support request can name the exact trace and log lines it is about. */
class TraceIdResponseHeaderFilterTest {

    private final SimpleTracer tracer = new SimpleTracer();
    private final TraceIdResponseHeaderFilter filter = new TraceIdResponseHeaderFilter(tracer);

    @Test
    void theResponseCarriesTheIdOfTheTraceTheRequestBelongsTo() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Span request = tracer.nextSpan().name("http post /v1/notifications").start();

        try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
            filter.doFilter(new MockHttpServletRequest("POST", "/v1/notifications"), response, new MockFilterChain());
        } finally {
            request.end();
        }

        assertThat(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER))
                .isNotBlank().isEqualTo(request.context().traceId());
    }

    @Test
    void withoutATraceNoHeaderIsAdded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/v1/templates"), response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER)).isNull();
    }
}
