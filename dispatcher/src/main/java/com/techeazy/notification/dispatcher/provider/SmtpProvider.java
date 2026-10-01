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

package com.techeazy.notification.dispatcher.provider;

import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.infra.SmtpSenderFactory;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMTP delivery. Settings: host, port, from, and optionally username, password, starttls (true/false), html (true/false).
 */
@Component
public class SmtpProvider implements ChannelProvider {

    private static final String HOST = "host";
    private static final String FROM = "from";
    private static final String HTML = "html";

    private record Key(java.util.UUID id, java.time.Instant updatedAt) {}

    private final Map<Key, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();

    @Override
    public ProviderType type() {
        return ProviderType.SMTP;
    }

    @Override
    public SendResult send(ProviderConfig config, Outbound message) {
        Map<String, String> s = config.getSettings();
        String from = require(s, FROM);
        try {
            JavaMailSenderImpl sender = senders.computeIfAbsent(new Key(config.getId(), config.getUpdatedAt()), k -> build(s));
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            applySender(helper, from, message);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.body(), Boolean.parseBoolean(s.getOrDefault(HTML, "false")));
            sender.send(mime);
            return new SendResult(mime.getMessageID());
        } catch (MailParseException | java.io.UnsupportedEncodingException e) {
            throw new PermanentSendException("Invalid email: " + e.getMessage(), e);
        } catch (jakarta.mail.MessagingException | org.springframework.mail.MailException e) {
            throw fail(e);
        }
    }

    private static void applySender(MimeMessageHelper helper, String defaultFrom, Outbound message) throws jakarta.mail.MessagingException, java.io.UnsupportedEncodingException {
        if (message.fromAddress() == null || message.fromAddress().isBlank()) {
            helper.setFrom(defaultFrom);
        } else if (message.fromName() == null || message.fromName().isBlank()) {
            helper.setFrom(message.fromAddress());
        } else {
            helper.setFrom(message.fromAddress(), message.fromName());
        }
    }

    private static RuntimeException fail(Exception e) {
        if (hasAddressError(e)) return new PermanentSendException("Invalid email address: " + e.getMessage(), e);
        return new TransientSendException("SMTP send failed: " + e.getMessage(), e);
    }

    /** True if the exception, or anything in its cause chain, is a malformed-address error. */
    private static boolean hasAddressError(Throwable error) {
        if (error instanceof AddressException) return true;
        Throwable cause = error.getCause();
        return cause != null && hasAddressError(cause);
    }

    private static JavaMailSenderImpl build(Map<String, String> s) {
        require(s, HOST);
        return SmtpSenderFactory.build(s);
    }

    private static String require(Map<String, String> s, String key) {
        String v = s.get(key);
        if (v == null || v.isBlank()) throw new TransientSendException("SMTP provider setting '" + key + "' is missing");
        return v;
    }
}
