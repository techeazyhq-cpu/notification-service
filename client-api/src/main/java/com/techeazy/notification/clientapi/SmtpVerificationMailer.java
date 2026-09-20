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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.infra.SmtpSenderFactory;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sends the confirmation mail through the first enabled SMTP e-mail provider an administrator configured, from that
 * provider's own address. It is the platform, not the client, that vouches for the message.
 */
@Component
public class SmtpVerificationMailer implements VerificationMailer {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpVerificationMailer.class);

    private final ProviderConfigRepository providers;

    public SmtpVerificationMailer(ProviderConfigRepository providers) {
        this.providers = providers;
    }

    @Override
    public void send(String toAddress, String clientName, String verificationUrl) {
        ProviderConfig provider = firstSmtpProvider().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_NOT_AVAILABLE", "No e-mail provider is configured, so the confirmation e-mail cannot be sent"));
        try {
            JavaMailSenderImpl sender = SmtpSenderFactory.build(provider.getSettings());
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(provider.getSettings().get("from"));
            helper.setTo(toAddress);
            helper.setSubject("Confirm this sender address for " + clientName);
            helper.setText("Someone using the notification account \"" + clientName + "\" asked to send e-mail from " + toAddress
                    + ".\n\nTo confirm that you control this address, open:\n" + verificationUrl
                    + "\n\nThe link works once and expires in 24 hours. If you did not ask for this, ignore this message.");
            sender.send(mime);
        } catch (jakarta.mail.MessagingException | RuntimeException e) {
            LOG.warn("Could not send sender confirmation to {}: {}", toAddress, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_NOT_AVAILABLE", "The confirmation e-mail could not be sent; try again later");
        }
    }

    private Optional<ProviderConfig> firstSmtpProvider() {
        return providers.findByChannelAndEnabledTrueOrderByPriorityAsc(Channel.EMAIL).stream()
                .filter(p -> p.getType() == ProviderType.SMTP).findFirst();
    }
}
