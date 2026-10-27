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

/**
 * Whether the caller can draft templates, for the UI to show or hide the assistant.
 *
 * @param enabled                 false when no API key is configured or drafting is off for clients
 * @param model                   the configured model; null when disabled
 * @param tokensLeftThisMonth     what the caller can still spend this calendar month (UTC), the smaller of the
 *                                platform's and, for a client, its own remaining budget; 0 when disabled
 * @param requestsPerMinute       how many drafts a caller may ask for per minute
 */
public record DraftingStatus(boolean enabled, String model, long tokensLeftThisMonth, int requestsPerMinute) {}
