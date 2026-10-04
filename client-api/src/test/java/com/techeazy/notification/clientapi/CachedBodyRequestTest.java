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
package com.techeazy.notification.clientapi;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A signed request's body is read once for the signature and then served again, multipart uploads included. */
class CachedBodyRequestTest {

    private static final String BOUNDARY = "signed-upload-boundary";
    private static final String CSV = "recipient,name\n+15550100,Ada\n+15550101,Grace\n";

    @Test
    void theBodyCanBeReadAgainAsOftenAsNeeded() throws IOException {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/v1/notifications");
        original.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        CachedBodyRequest cached = CachedBodyRequest.read(original, 1024);

        assertThat(StreamUtils.copyToString(cached.getInputStream(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThat(cached.getReader().readLine()).isEqualTo("{\"a\":1}");
        assertThat(cached.getContentLengthLong()).isEqualTo(7);
    }

    @Test
    void aBodyLongerThanTheLimitIsRefused() {
        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/v1/notifications");
        declared.setContent(new byte[2048]);

        assertThatThrownBy(() -> CachedBodyRequest.read(declared, 1024))
                .isInstanceOf(CachedBodyRequest.BodyTooLargeException.class);
    }

    @Test
    void springSeesTheUploadedFileAndEveryFieldOfASignedMultipartRequest() throws IOException {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/v1/notifications/bulk/upload");
        original.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        original.setQueryString("clientReference=batch%2042");
        original.setContent(multipart().getBytes(StandardCharsets.UTF_8));

        CachedBodyRequest cached = CachedBodyRequest.read(original, 1024 * 1024);
        MultipartHttpServletRequest resolved = new StandardServletMultipartResolver().resolveMultipart(cached);

        MultipartFile file = resolved.getFile("file");
        assertThat(file).isNotNull();
        assertThat(file.getOriginalFilename()).isEqualTo("recipients.csv");
        assertThat(new String(file.getBytes(), StandardCharsets.UTF_8)).isEqualTo(CSV);
        assertThat(resolved.getParameter("channel")).isEqualTo("SMS");
        assertThat(resolved.getParameter("body")).isEqualTo("Hello {{name}}");
        assertThat(resolved.getParameter("clientReference")).isEqualTo("batch 42");
        assertThat(resolved.getParameter("missing")).isNull();
    }

    private static String multipart() {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"channel\"\r\n\r\n"
                + "SMS\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"body\"\r\n\r\n"
                + "Hello {{name}}\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"recipients.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + CSV + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }
}
