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

package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.domain.Channel;

import java.util.UUID;

/** What a client asks to send: how many messages on which channel, and the request or message they belong to. */
public record Admission(UUID clientId, Channel channel, long messages, HoldScope scope, UUID referenceId) {}
