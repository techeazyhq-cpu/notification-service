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

import com.techeazy.notification.error.TraceIdSource;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Reads the trace id from the Micrometer tracer, the same id {@link TraceIdResponseHeaderFilter} returns. */
@Component
public class TracerTraceIdSource implements TraceIdSource {

    private final Tracer tracer;

    public TracerTraceIdSource(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Optional<String> currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? Optional.empty() : Optional.of(current.context().traceId());
    }
}
