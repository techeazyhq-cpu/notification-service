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

package com.techeazy.notification.infra;

import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;
import java.util.Properties;

/** Builds a mail sender from the settings of an SMTP provider ({@code host}, {@code port}, {@code username}, {@code password}, {@code starttls}). */
public final class SmtpSenderFactory {

    private SmtpSenderFactory() {
    }

    public static JavaMailSenderImpl build(Map<String, String> settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.get("host"));
        sender.setPort(Integer.parseInt(settings.getOrDefault("port", "25")));
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        String username = settings.get("username");
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(settings.get("password"));
            properties.put("mail.smtp.auth", "true");
        }
        if (Boolean.parseBoolean(settings.getOrDefault("starttls", "false"))) {
            properties.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }
}
