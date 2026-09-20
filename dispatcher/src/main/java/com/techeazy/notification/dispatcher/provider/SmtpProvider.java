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
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMTP delivery. Settings: host, port, from, and optionally username, password, starttls (true/false), html (true/false).
 */
@Component
public class SmtpProvider implements ChannelProvider {

    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String FROM = "from";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String STARTTLS = "starttls";
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
            helper.setFrom(from);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.body(), Boolean.parseBoolean(s.getOrDefault(HTML, "false")));
            sender.send(mime);
            return new SendResult(mime.getMessageID());
        } catch (MailParseException e) {
            throw new PermanentSendException("Invalid email: " + e.getMessage(), e);
        } catch (jakarta.mail.MessagingException | org.springframework.mail.MailException e) {
            throw fail(e);
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
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(require(s, HOST));
        sender.setPort(Integer.parseInt(s.getOrDefault(PORT, "25")));
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        String username = s.get(USERNAME);
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(s.get(PASSWORD));
            p.put("mail.smtp.auth", "true");
        }
        if (Boolean.parseBoolean(s.getOrDefault(STARTTLS, "false"))) p.put("mail.smtp.starttls.enable", "true");
        return sender;
    }

    private static String require(Map<String, String> s, String key) {
        String v = s.get(key);
        if (v == null || v.isBlank()) throw new TransientSendException("SMTP provider setting '" + key + "' is missing");
        return v;
    }
}
