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

    record Row(String recipient, Map<String, String> variables) {}

    private CsvRecipientParser() {}

    static List<Row> parse(InputStream in, int maxRows) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build();
        try (CSVParser parser = format.parse(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String recipientHeader = parser.getHeaderNames().stream()
                    .filter(h -> h.replace("﻿", "").trim().equalsIgnoreCase("recipient"))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("CSV must have a 'recipient' header column"));
            List<Row> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() >= maxRows) {
                    throw ApiException.badRequest("CSV exceeds the maximum of " + maxRows + " recipients");
                }
                Map<String, String> vars = new LinkedHashMap<>();
                for (String header : parser.getHeaderNames()) {
                    if (!header.equals(recipientHeader) && record.isSet(header)) vars.put(header, record.get(header));
                }
                rows.add(new Row(record.get(recipientHeader), vars));
            }
            return rows;
        }
    }
}
