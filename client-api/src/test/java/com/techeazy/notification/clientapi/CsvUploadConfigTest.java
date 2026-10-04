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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The bulk CSV upload accepts the documented 20 MB, and Spring Boot's own multipart defaults do not apply. */
class CsvUploadConfigTest {

    private static final long TWENTY_MEGABYTES = 20L * 1024 * 1024;

    @Test
    void uploadsAreCappedAtTheDocumentedTwentyMegabytes() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
                .withUserConfiguration(CsvUploadConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MultipartConfigElement.class);
                    MultipartConfigElement multipart = context.getBean(MultipartConfigElement.class);
                    assertThat(multipart.getMaxFileSize()).isEqualTo(TWENTY_MEGABYTES);
                    assertThat(multipart.getMaxRequestSize()).isEqualTo(TWENTY_MEGABYTES);
                });
    }
}
