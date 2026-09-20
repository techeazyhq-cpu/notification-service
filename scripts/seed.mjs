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

// Seeds a local environment through the Admin API: catcher providers, sample templates, a demo client.
//   node scripts/seed.mjs
// Env: ADMIN_URL (http://localhost:8081), ADMIN_USER/ADMIN_PASSWORD (admin/admin, or your changed password),
//      ADMIN_OTP (a current two-factor code, only if two-factor authentication is enabled),
//      SMTP_HOST/SMTP_PORT (mailpit/1025) and CATCHER_URL (http://catcher:9000) are the addresses the
//      *dispatcher* uses, i.e. Docker service names when the stack runs in Compose. When running the
//      dispatcher on your host instead, use SMTP_HOST=localhost CATCHER_URL=http://localhost:9000.
const ADMIN = process.env.ADMIN_URL || 'http://localhost:8081'; // NOSONAR: plain-HTTP default is for local development only
let auth = '';
const SMTP_HOST = process.env.SMTP_HOST || 'mailpit';
const SMTP_PORT = process.env.SMTP_PORT || '1025';
const CATCHER = process.env.CATCHER_URL || 'http://catcher:9000'; // NOSONAR: in-cluster catcher for local development only

async function signIn() {
  const res = await fetch(ADMIN + '/api/admin/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: process.env.ADMIN_USER || 'admin',
      password: process.env.ADMIN_PASSWORD || 'admin',
      verificationCode: process.env.ADMIN_OTP || null,
    }),
  });
  if (!res.ok) throw new Error(`Sign-in failed: ${res.status} ${await res.text()}`);
  auth = 'Bearer ' + (await res.json()).token;
}

async function api(method, path, body) {
  const res = await fetch(ADMIN + '/api/admin' + path, {
    method,
    headers: { Authorization: auth, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function ensure(path, name, body) {
  const existing = (await api('GET', path)).find((x) => x.name === name);
  if (existing) { console.log(`= ${path}/${name} exists`); return existing; }
  const created = await api('POST', path, body);
  console.log(`+ ${path}/${name}`);
  return created;
}

await signIn();
await ensure('/providers', 'mailpit-smtp', {
  channel: 'EMAIL', name: 'mailpit-smtp', type: 'SMTP', enabled: true, priority: 10,
  settings: { host: SMTP_HOST, port: SMTP_PORT, from: 'no-reply@notify.local' },
});
for (const ch of ['sms', 'whatsapp', 'push']) {
  await ensure('/providers', `catcher-${ch}`, {
    channel: ch.toUpperCase(), name: `catcher-${ch}`, type: 'HTTP_JSON', enabled: true, priority: 10,
    settings: { url: `${CATCHER}/${ch}` },
  });
}

await ensure('/templates', 'welcome-email', {
  name: 'welcome-email', channel: 'EMAIL', subject: 'Welcome, {{name}}!',
  body: 'Hi {{name}},\n\nThanks for joining. Your account email is {{recipient}}.\n',
});
await ensure('/templates', 'otp-sms', { name: 'otp-sms', channel: 'SMS', body: 'Your verification code is {{code}}. It expires in 5 minutes.' });
await ensure('/templates', 'order-whatsapp', { name: 'order-whatsapp', channel: 'WHATSAPP', body: 'Hi {{name}}, your order {{orderId}} has shipped.' });
await ensure('/templates', 'promo-push', { name: 'promo-push', channel: 'PUSH', subject: 'Flash sale', body: '{{name}}, 20% off today only.' });

const clientExists = (await api('GET', '/clients')).some((c) => c.name === 'demo-app');
if (clientExists) {
  console.log('= /clients/demo-app exists (API key is only shown at creation; rotate it in the admin UI if lost)');
} else {
  const { apiKey } = await api('POST', '/clients', { name: 'demo-app', allowedChannels: ['EMAIL', 'SMS', 'WHATSAPP', 'PUSH'] });
  console.log(`+ /clients/demo-app\n\n  API key (shown once): ${apiKey}\n`);
}
