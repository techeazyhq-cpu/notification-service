/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.drafting;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;

import java.util.List;

/**
 * A ready-made template an author can start from without a model (ADR-046). It meets its channel's rules as written;
 * the author edits it and saves it as a template of their own.
 *
 * @param id          a stable, readable identifier, such as {@code sms-otp-verification}; a translation adds its
 *                    language, as in {@code sms-otp-verification-hi}
 * @param language    the language of the subject and body, as a BCP 47 tag such as {@code en} or {@code ar}
 * @param name        a short label for a picker, in English like the rest of the console
 * @param description what the message is for
 * @param subject     the e-mail subject or push title; null for SMS and WhatsApp
 * @param variables   the placeholders a sender supplies, in order of first appearance
 * @param smsLength   how long the SMS is; null for other channels
 */
public record TemplateExample(String id, String language, Channel channel, MessageCategory category, String name,
                              String description,
                              String subject, String body, List<String> variables,
                              ChannelRules.SmsLength smsLength) {}
