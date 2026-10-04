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
import { canonical, multipartBody, newNonce, signatureHeaders } from '../src/signing.ts';

const encode = (text) => new TextEncoder().encode(text);
const AT = new Date(1767225600 * 1000);

test('a request signs to the same value the service and the client guide use', async () => {
  const headers = await signatureHeaders('nss_test', 'POST', '/v1/notifications', encode('{"channel":"SMS"}'), AT,
    '0123456789abcdef');

  assert.deepEqual(headers, {
    'X-Signature-Timestamp': '1767225600',
    'X-Signature-Nonce': '0123456789abcdef',
    'X-Signature': '2295ae2e9e31c56816963cbcc04632396fa6471d25a8cb667f9003711e053503',
  });
});

test('the query is signed without its question mark, and no body hashes no bytes', async () => {
  const text = await canonical('1767225600', '0123456789abcdef', 'get', '/v1/notifications', 'status=SENT&page=2',
    new Uint8Array());
  const headers = await signatureHeaders('nss_test', 'GET', '/v1/notifications?status=SENT&page=2', undefined, AT,
    '0123456789abcdef');

  assert.ok(text.startsWith('NS1-HMAC-SHA256\n1767225600\n0123456789abcdef\nGET\n/v1/notifications\nstatus=SENT&page=2\n'));
  assert.ok(text.endsWith('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'));
  assert.equal(headers['X-Signature'], '32ec965cf932dc40b326dbfd2e2bd479de8e96a91fdfcac571378f7ebcfc00dd');
});

test('every request gets a different, well-formed nonce', () => {
  const nonces = new Set(Array.from({ length: 50 }, newNonce));

  assert.equal(nonces.size, 50);
  for (const nonce of nonces) assert.match(nonce, /^[0-9a-f]{32}$/);
});

test('a hand-built multipart body carries every field and the file under one boundary', () => {
  const { body, contentType } = multipartBody({ channel: 'SMS', body: 'Hi {{name}}' },
    { field: 'file', name: 'recipients.csv', type: 'text/csv', content: 'recipient,name\n+15550100,Ada\n' });
  const text = new TextDecoder().decode(body);
  const boundary = contentType.split('boundary=')[1];

  assert.match(contentType, /^multipart\/form-data; boundary=----nsBoundary[0-9a-f]{32}$/);
  assert.ok(text.includes(`--${boundary}\r\nContent-Disposition: form-data; name="channel"\r\n\r\nSMS\r\n`));
  assert.ok(text.includes('filename="recipients.csv"\r\nContent-Type: text/csv\r\n\r\nrecipient,name\n+15550100,Ada\n\r\n'));
  assert.ok(text.endsWith(`--${boundary}--\r\n`));
});
