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

import type { SmsLength } from './api';

/** Variable names typed as "firstName, orderId" or "{{firstName}} {{orderId}}", without braces or repeats. */
export function parseVariables(text: string): string[] {
  const names = text.split(/[\s,;]+/)
    .map((name) => name.replace(/^\{\{\s*/, '').replace(/\s*\}\}$/, '').trim())
    .filter(Boolean);
  return [...new Set(names)];
}

/** "1 segment, GSM-7, about 87 characters" for the estimate the server makes of an SMS draft. */
export function smsSummary(length: SmsLength): string {
  const segments = length.segments === 1 ? '1 segment' : `${length.segments} segments`;
  return `${segments}, ${length.encoding}, about ${length.characters} characters`;
}
