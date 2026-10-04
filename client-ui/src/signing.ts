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

/**
 * Signs client API requests as the service verifies them (ADR-036, "Signed requests" in the client guide): an
 * HMAC-SHA256, keyed by the client's signing secret, over the method, path, query and exact body bytes, bound to a
 * timestamp and a single-use nonce. Uses only Web Crypto, so it runs in the browser and in Node's test runner.
 */

const SCHEME = 'NS1-HMAC-SHA256';
const encoder = new TextEncoder();

const hex = (bytes: ArrayBuffer): string => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('');

export async function sha256Hex(body: Uint8Array<ArrayBuffer>): Promise<string> {
  return hex(await crypto.subtle.digest('SHA-256', body));
}

/** The seven lines a request's signature covers, joined by line feeds. */
export async function canonical(timestamp: string, nonce: string, method: string, path: string, query: string, body: Uint8Array<ArrayBuffer>): Promise<string> {
  return [SCHEME, timestamp, nonce, method.toUpperCase(), path, query, await sha256Hex(body)].join('\n');
}

export async function hmacHex(secret: string, text: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', key, encoder.encode(text)));
}

/** A fresh nonce for every request, retries included: 32 hex characters of randomness. */
export function newNonce(): string {
  return hex(crypto.getRandomValues(new Uint8Array(16)).buffer);
}

/**
 * The headers that sign one request. `url` is what is passed to fetch, relative or absolute; the path and query are
 * taken as the browser will send them.
 */
export async function signatureHeaders(
  secret: string, method: string, url: string, body: Uint8Array<ArrayBuffer> = new Uint8Array(),
  now: Date = new Date(), nonce: string = newNonce(),
): Promise<Record<string, string>> {
  const target = new URL(url, 'https://placeholder.invalid');
  const timestamp = Math.floor(now.getTime() / 1000).toString();
  const text = await canonical(timestamp, nonce, method, target.pathname, target.search.replace(/^\?/, ''), body);
  return {
    'X-Signature-Timestamp': timestamp,
    'X-Signature-Nonce': nonce,
    'X-Signature': await hmacHex(secret, text),
  };
}

/**
 * Builds a multipart/form-data body by hand, so the exact bytes are known before sending and can be signed; a
 * FormData body is only serialised by the browser as it sends it.
 */
export function multipartBody(fields: Record<string, string>, file: { field: string; name: string; type: string; content: string }): { body: Uint8Array<ArrayBuffer>; contentType: string } {
  const boundary = `----nsBoundary${newNonce()}`;
  const parts: string[] = [];
  for (const [name, value] of Object.entries(fields)) {
    parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`);
  }
  parts.push(
    `--${boundary}\r\nContent-Disposition: form-data; name="${file.field}"; filename="${file.name}"\r\n`
      + `Content-Type: ${file.type}\r\n\r\n${file.content}\r\n`,
    `--${boundary}--\r\n`,
  );
  return { body: encoder.encode(parts.join('')), contentType: `multipart/form-data; boundary=${boundary}` };
}
