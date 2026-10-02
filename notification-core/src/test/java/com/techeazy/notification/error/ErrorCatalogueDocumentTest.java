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
package com.techeazy.notification.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/error-codes.md is generated from {@link ErrorCode}, so the published dictionary can never drift from the codes
 * the services return. After changing the catalogue, regenerate it with
 * {@code mvn -pl notification-core test -Dtest=ErrorCatalogueDocumentTest -Derror-catalogue.update=true}.
 */
class ErrorCatalogueDocumentTest {

    private static final Path DOCUMENT = Path.of("..", "docs", "error-codes.md");

    @Test
    void thePublishedDictionaryMatchesTheCatalogue() throws IOException {
        String generated = ErrorCatalogue.markdown();
        if (Boolean.getBoolean("error-catalogue.update")) {
            Files.writeString(DOCUMENT, generated, StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(DOCUMENT, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("docs/error-codes.md is stale; regenerate it as this test's Javadoc describes")
                .isEqualTo(generated);
    }

    @Test
    void everyCodeHasAnAnchorNamedAfterItsErrorId() {
        String document = ErrorCatalogue.markdown();

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(document).contains("### " + code.errorId() + " " + code.code());
        }
    }
}
