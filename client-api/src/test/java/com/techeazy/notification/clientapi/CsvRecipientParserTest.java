package com.techeazy.notification.clientapi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvRecipientParserTest {

    private static final char BOM = 0xFEFF;

    private static ByteArrayInputStream csv(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void recipientColumnBecomesAddressAndOthersBecomeVariables() throws Exception {
        List<CsvRecipientParser.Row> rows = CsvRecipientParser.parse(
                csv("Recipient,name,code\nann@example.com,Ann,123\nbob@example.com,\"Bob, Jr\",456\n"), 100);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).recipient()).isEqualTo("ann@example.com");
        assertThat(rows.get(0).variables()).containsEntry("name", "Ann").containsEntry("code", "123");
        assertThat(rows.get(1).variables()).containsEntry("name", "Bob, Jr");
    }

    @Test
    void toleratesUtf8BomAndBlankLines() throws Exception {
        var rows = CsvRecipientParser.parse(csv(BOM + "recipient\n\na@example.com\n\n"), 100);
        assertThat(rows).extracting(CsvRecipientParser.Row::recipient).containsExactly("a@example.com");
    }

    @Test
    void requiresRecipientHeader() {
        var input = csv("email\na@example.com\n");
        assertThatThrownBy(() -> CsvRecipientParser.parse(input, 100))
                .isInstanceOf(ApiException.class).hasMessageContaining("recipient");
    }

    @Test
    void enforcesRowLimit() {
        var input = csv("recipient\na@x.io\nb@x.io\nc@x.io\n");
        assertThatThrownBy(() -> CsvRecipientParser.parse(input, 2))
                .isInstanceOf(ApiException.class).hasMessageContaining("maximum");
    }
}
