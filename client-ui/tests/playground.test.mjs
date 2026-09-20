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

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CATALOG } from '../src/playgroundCatalog.ts';
import { buildCurl, buildUrl, formatBody, safeParse, shellQuote } from '../src/playgroundLogic.ts';

const endpoint = (id) => CATALOG.find((e) => e.id === id);

test('every endpoint is unique and its sample body is valid JSON', () => {
  assert.equal(new Set(CATALOG.map((e) => e.id)).size, CATALOG.length);
  for (const e of CATALOG) {
    assert.ok(e.path.startsWith('/v1/'), e.id);
    if (e.body !== undefined) assert.notEqual(safeParse(JSON.stringify(e.body)), null, e.id);
  }
});

test('every path placeholder has an input and every input a placeholder', () => {
  for (const e of CATALOG) {
    const placeholders = [...e.path.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
    assert.deepEqual(Object.keys(e.pathParams ?? {}).sort(), placeholders, e.id);
  }
});

test('sending endpoints are flagged live and read-only ones are not', () => {
  assert.deepEqual(CATALOG.filter((e) => e.live).map((e) => e.id).sort(), ['bulk', 'send', 'upload']);
  assert.ok(CATALOG.filter((e) => e.method === 'GET').every((e) => !e.live));
});

test('buildUrl fills and encodes path values and keeps only filled query values', () => {
  assert.equal(buildUrl(endpoint('status'), { requestId: 'a/b c' }, {}), '/v1/notifications/a%2Fb%20c');
  assert.equal(buildUrl(endpoint('list'), {}, { channel: 'SMS', clientReference: ' ', page: '0', size: '' }), '/v1/notifications?channel=SMS&page=0');
  assert.equal(buildUrl(endpoint('me'), {}, {}), '/v1/me');
});

test('shellQuote survives single quotes', () => {
  assert.equal(shellQuote("it's"), "'it'\''s'");
});

test('curl for a JSON call uses the key placeholder, the body and the idempotency key', () => {
  const body = JSON.stringify({ text: "it's" }, null, 2);
  const curl = buildCurl(endpoint('send'), 'https://api.example.com', '/v1/notifications', body, ' key-1 ');

  assert.match(curl, /^curl -X POST 'https:\/\/api\.example\.com\/v1\/notifications'/);
  assert.match(curl, /-H "X-API-Key: \$API_KEY"/);
  assert.match(curl, /-H 'Idempotency-Key: key-1'/);
  assert.match(curl, /-H "Content-Type: application\/json"/);
  assert.ok(curl.includes("it'\''s"));
});

test('curl for a GET has no body and no content type', () => {
  const curl = buildCurl(endpoint('me'), 'http://localhost:5174', '/v1/me', '', '');

  assert.equal(curl, 'curl -X GET \'http://localhost:5174/v1/me\' \\n  -H "X-API-Key: $API_KEY"');
});

test('curl for the CSV upload becomes form fields with the file left to the caller', () => {
  const body = JSON.stringify({ channel: 'SMS', body: 'Hi {{name}}', csv: 'recipient\n+1' });
  const curl = buildCurl(endpoint('upload'), 'http://x', '/v1/notifications/bulk/upload', body, '');

  assert.match(curl, /-F 'channel=SMS'/);
  assert.match(curl, /-F 'body=Hi \{\{name\}\}'/);
  assert.match(curl, /-F "file=@recipients.csv"/);
  assert.ok(!curl.includes('recipient\n+1'));
});

test('JSON responses are pretty printed and other content is left alone', () => {
  assert.equal(formatBody('{"a":1}', 'application/json'), '{\n  "a": 1\n}');
  assert.equal(formatBody('not json', 'application/json'), 'not json');
  assert.equal(formatBody('a,b\n1,2', 'text/csv'), 'a,b\n1,2');
  assert.ok(formatBody('x'.repeat(200_001), 'text/plain').endsWith('(truncated)'));
});
