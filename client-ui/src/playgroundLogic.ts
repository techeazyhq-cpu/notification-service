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

import type { PlaygroundEndpoint } from './playgroundCatalog';

const MAX_BODY_CHARS = 200_000;

export const pretty = (value: unknown) => JSON.stringify(value, null, 2);

export function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function shellQuote(text: string): string {
  return `'${text.replaceAll("'", `'\''`)}'`;
}

export function buildUrl(endpoint: PlaygroundEndpoint, pathValues: Record<string, string>, queryValues: Record<string, string>): string {
  let path = endpoint.path;
  for (const [name, value] of Object.entries(pathValues)) path = path.replace(`{${name}}`, encodeURIComponent(value));
  const query = new URLSearchParams(Object.entries(queryValues).filter(([, value]) => value.trim() !== ''));
  const text = query.toString();
  return text === '' ? path : `${path}?${text}`;
}

export function formatBody(text: string, contentType: string | null): string {
  const clipped = text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) + '\n… (truncated)' : text;
  if (contentType?.includes('json') && text.length <= MAX_BODY_CHARS) {
    const parsed = safeParse(text);
    return parsed === null && text.trim() !== 'null' ? clipped : pretty(parsed);
  }
  return clipped;
}

/** The same call as a curl command; the key is always the placeholder $API_KEY, never the real one. */
export function buildCurl(endpoint: PlaygroundEndpoint, origin: string, url: string, body: string, idempotencyKey: string): string {
  const lines = [`curl -X ${endpoint.method} ${shellQuote(origin + url)}`, '  -H "X-API-Key: $API_KEY"'];
  if (idempotencyKey.trim()) lines.push(`  -H ${shellQuote('Idempotency-Key: ' + idempotencyKey.trim())}`);
  if (endpoint.upload) {
    const fields = (safeParse(body) ?? {}) as Record<string, string>;
    for (const [name, value] of Object.entries(fields)) {
      if (name !== 'csv' && value) lines.push(`  -F ${shellQuote(`${name}=${value}`)}`);
    }
    lines.push('  -F "file=@recipients.csv"');
  } else if (endpoint.body !== undefined) {
    lines.push('  -H "Content-Type: application/json"', `  -d ${shellQuote(body)}`);
  }
  return lines.join(' \\n');
}
