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

    private record Key(java.util.UUID id, java.time.Instant updatedAt) {}

    private final Map<Key, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();

    @Override
    public ProviderType type() {
        return ProviderType.SMTP;
    }

    @Override
    public SendResult send(ProviderConfig config, Outbound message) {
        Map<String, String> s = config.getSettings();
        String from = require(s, "from");
        try {
            JavaMailSenderImpl sender = senders.computeIfAbsent(new Key(config.getId(), config.getUpdatedAt()), k -> build(s));
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject() == null ? "" : message.subject());
            helper.setText(message.body(), Boolean.parseBoolean(s.getOrDefault("html", "false")));
            sender.send(mime);
            return new SendResult(mime.getMessageID());
        } catch (MailParseException e) {
            throw new PermanentSendException("Invalid email: " + e.getMessage(), e);
        } catch (jakarta.mail.MessagingException e) {
            throw fail(e);
        } catch (org.springframework.mail.MailException e) {
            throw fail(e);
        }
    }

    private static RuntimeException fail(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AddressException) return new PermanentSendException("Invalid email address: " + t.getMessage(), e);
        }
        return new TransientSendException("SMTP send failed: " + e.getMessage(), e);
    }

    private static JavaMailSenderImpl build(Map<String, String> s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(require(s, "host"));
        sender.setPort(Integer.parseInt(s.getOrDefault("port", "25")));
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        if (s.get("username") != null && !s.get("username").isBlank()) {
            sender.setUsername(s.get("username"));
            sender.setPassword(s.get("password"));
            p.put("mail.smtp.auth", "true");
        }
        if (Boolean.parseBoolean(s.getOrDefault("starttls", "false"))) p.put("mail.smtp.starttls.enable", "true");
        return sender;
    }

    private static String require(Map<String, String> s, String key) {
        String v = s.get(key);
        if (v == null || v.isBlank()) throw new TransientSendException("SMTP provider setting '" + key + "' is missing");
        return v;
    }
}
