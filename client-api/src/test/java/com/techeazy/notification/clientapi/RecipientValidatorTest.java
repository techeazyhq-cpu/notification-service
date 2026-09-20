package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientValidatorTest {

    @Test
    void acceptsValidRecipients() {
        assertThat(RecipientValidator.check(Channel.EMAIL, "a.b@example.com")).isNull();
        assertThat(RecipientValidator.check(Channel.SMS, "+14155550123")).isNull();
        assertThat(RecipientValidator.check(Channel.WHATSAPP, "+919876543210")).isNull();
        assertThat(RecipientValidator.check(Channel.PUSH, "fcm-token-123")).isNull();
    }

    @Test
    void rejectsInvalidRecipients() {
        assertThat(RecipientValidator.check(Channel.EMAIL, "not-an-email")).isNotNull();
        assertThat(RecipientValidator.check(Channel.SMS, "4155550123")).isNotNull();   // missing +
        assertThat(RecipientValidator.check(Channel.WHATSAPP, "+0123456789")).isNotNull(); // leading 0 country code
        assertThat(RecipientValidator.check(Channel.PUSH, " ")).isNotNull();
    }
}
