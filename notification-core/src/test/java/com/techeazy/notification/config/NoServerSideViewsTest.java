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

package com.techeazy.notification.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the {@code .trivyignore} entry for CVE-2026-47884 true: that Spring MVC flaw is only reachable through
 * server-side views ({@code XsltView}), and every service here is a JSON API that renders none. If a view is ever
 * introduced, this fails, and the ignore entry has to go with a real fix instead.
 */
class NoServerSideViewsTest {

    private static final List<String> VIEW_TYPES = List.of("org.springframework.web.servlet.view.xslt", "XsltView",
            "ViewResolver", "ModelAndView");

    @Test
    void noServiceRendersServerSideViews() throws IOException {
        List<String> offending;
        try (Stream<Path> sources = Files.walk(Path.of(".."))) {
            offending = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(NoServerSideViewsTest::mentionsAView)
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offending).as("main sources that use server-side views (see .trivyignore, CVE-2026-47884)").isEmpty();
    }

    private static boolean mentionsAView(Path source) {
        try {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            return VIEW_TYPES.stream().anyMatch(code::contains);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + source, e);
        }
    }
}
