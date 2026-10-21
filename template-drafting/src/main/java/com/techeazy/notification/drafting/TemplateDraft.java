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

package com.techeazy.notification.drafting;

import com.techeazy.notification.domain.Channel;

import java.util.List;

/**
 * A draft that met its channel's rules. It is only shown to the author: nothing is saved or sent until they save it
 * through the ordinary template endpoints.
 *
 * @param subject   the e-mail subject; null on other channels
 * @param variables the placeholders the draft uses, in order of first appearance, without {@code recipient}
 * @param warnings  points the author should look at, such as an SMS of two segments or an unused placeholder
 * @param smsLength the estimated SMS length; null on other channels
 * @param model     the model that wrote the draft
 */
public record TemplateDraft(Channel channel, String subject, String body, List<String> variables,
                            List<String> warnings, ChannelRules.SmsLength smsLength, String model) {}
