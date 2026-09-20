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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a CSV with a header row. The {@code recipient} column is the address; every other column
 * becomes a template variable named after its header.
 */
final class CsvRecipientParser {

    /** Byte order mark that spreadsheet tools prepend to UTF-8 CSV files; it ends up glued to the first header. */
    private static final char BOM = 0xFEFF;

    record Row(String recipient, Map<String, String> variables) {}

    private CsvRecipientParser() {}

    static List<Row> parse(InputStream in, int maxRows) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = format.parse(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String recipientHeader = parser.getHeaderNames().stream()
                    .filter(h -> h.replace(String.valueOf(BOM), "").trim().equalsIgnoreCase("recipient"))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("CSV must have a 'recipient' header column"));
            List<Row> rows = new ArrayList<>();
            for (CSVRecord csvRow : parser) {
                if (rows.size() >= maxRows) {
                    throw ApiException.badRequest("CSV exceeds the maximum of " + maxRows + " recipients");
                }
                Map<String, String> vars = new LinkedHashMap<>();
                for (String header : parser.getHeaderNames()) {
                    if (!header.equals(recipientHeader) && csvRow.isSet(header)) vars.put(header, csvRow.get(header));
                }
                rows.add(new Row(csvRow.get(recipientHeader), vars));
            }
            return rows;
        }
    }
}
