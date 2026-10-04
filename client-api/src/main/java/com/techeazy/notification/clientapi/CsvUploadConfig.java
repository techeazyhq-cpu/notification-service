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

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Caps multipart uploads, whose only use is the bulk CSV upload, at {@value #MAX_UPLOAD_MEGABYTES} MB.
 * <p>
 * The cap is above the 8 MB Sonar recommends (java:S5693) on purpose: a CSV of the 50,000 recipients a bulk request
 * may carry, each row with a few template variables, needs more than 8 MB. The cap is part of the published contract
 * (client guide) and of the denial-of-service controls in the design's threat model, together with the per-client
 * rate limit and the row cap enforced while the file is streamed ({@link CsvRecipientParser}).
 */
@Configuration
class CsvUploadConfig {

    static final long MAX_UPLOAD_MEGABYTES = 20;

    @Bean
    @SuppressWarnings("java:S5693")
    MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(MAX_UPLOAD_MEGABYTES));
        factory.setMaxRequestSize(DataSize.ofMegabytes(MAX_UPLOAD_MEGABYTES));
        return factory.createMultipartConfig();
    }
}
