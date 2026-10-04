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

import { useState } from 'react';
import { api, Channel, CHANNELS, Client } from '../api';
import { useLoad } from '../hooks';

export default function Clients() {
  const { data, error, reload } = useLoad(() => api<Client[]>('GET', '/clients'));
  const [name, setName] = useState('');
  const [channels, setChannels] = useState<Channel[]>([...CHANNELS]);
  const [issued, setIssued] = useState<{ name: string; what: string; value: string } | null>(null);
  const [formError, setFormError] = useState('');

  async function create(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await api<{ client: Client; apiKey: string }>('POST', '/clients', { name, allowedChannels: channels });
      setIssued({ name: r.client.name, what: 'API key', value: r.apiKey });
      setName('');
      setFormError('');
      reload();
    } catch (err) { setFormError((err as Error).message); }
  }

  async function rotate(c: Client) {
    if (!confirm(`Rotate the API key for ${c.name}? The old key stops working within 30 seconds.`)) return;
    const r = await api<{ client: Client; apiKey: string }>('POST', `/clients/${c.id}/rotate-key`);
    setIssued({ name: c.name, what: 'API key', value: r.apiKey });
    reload();
  }

  async function issueSigningSecret(c: Client) {
    const warning = c.signingEnabled
      ? `Replace the signing secret for ${c.name}? Requests signed with the old secret are refused within 30 seconds.`
      : `Issue a request-signing secret for ${c.name}? Signing stays optional until you require it.`;
    if (!confirm(warning)) return;
    const r = await api<{ client: Client; signingSecret: string }>('POST', `/clients/${c.id}/signing-secret`);
    setIssued({ name: c.name, what: 'Signing secret', value: r.signingSecret });
    reload();
  }

  async function requireSignatures(c: Client, required: boolean) {
    const warning = required
      ? `Require signed requests from ${c.name}? Unsigned sends and changes are refused within 30 seconds.`
      : `Make signing optional for ${c.name}? Unsigned requests are accepted again.`;
    if (!confirm(warning)) return;
    await api('PUT', `/clients/${c.id}/signing-required`, { required });
    reload();
  }

  async function removeSigningSecret(c: Client) {
    if (!confirm(`Remove the signing secret for ${c.name}? Signed requests are refused and unsigned ones accepted.`)) return;
    await api('DELETE', `/clients/${c.id}/signing-secret`);
    reload();
  }

  async function toggle(c: Client) {
    await api('PUT', `/clients/${c.id}`, { name: c.name, status: c.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE', allowedChannels: c.allowedChannels });
    reload();
  }

  const toggleChannel = (ch: Channel) => setChannels((cur) => (cur.includes(ch) ? cur.filter((x) => x !== ch) : [...cur, ch]));

  return (
    <>
      <h1>Clients</h1>
      {issued && (
        <div className="notice">
          {issued.what} for <b>{issued.name}</b> — copy it now, it is not shown again:<br />
          <span className="mono">{issued.value}</span>{' '}
          <button type="button" onClick={() => navigator.clipboard.writeText(issued.value)}>Copy</button>{' '}
          <button type="button" className="link" onClick={() => setIssued(null)}>Dismiss</button>
        </div>
      )}
      <form className="card row" onSubmit={create}>
        <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} /></label>
        {CHANNELS.map((ch) => (
          <label className="check" key={ch}><input type="checkbox" checked={channels.includes(ch)} onChange={() => toggleChannel(ch)} />{ch}</label>
        ))}
        <button type="submit" className="primary" disabled={!channels.length}>Create client</button>
        {formError && <span className="error">{formError}</span>}
      </form>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Status</th><th>Channels</th><th>Key</th><th>Signed requests</th><th></th></tr></thead>
          <tbody>
            {data?.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td><span className={`badge ${c.status}`}>{c.status}</span></td>
                <td>{c.allowedChannels.join(', ')}</td>
                <td className="mono">{c.apiKeyPrefix}…</td>
                <td>{signingLabel(c)}</td>
                <td>
                  <button type="button" onClick={() => toggle(c)}>{c.status === 'ACTIVE' ? 'Disable' : 'Enable'}</button>{' '}
                  <button type="button" onClick={() => rotate(c)}>Rotate key</button>{' '}
                  <button type="button" onClick={() => issueSigningSecret(c)}>
                    {c.signingEnabled ? 'Rotate signing secret' : 'Issue signing secret'}
                  </button>
                  {c.signingEnabled && (
                    <>
                      {' '}
                      <button type="button" onClick={() => requireSignatures(c, !c.signingRequired)}>
                        {c.signingRequired ? 'Make signing optional' : 'Require signatures'}
                      </button>{' '}
                      <button type="button" onClick={() => removeSigningSecret(c)}>Remove signing secret</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={6} className="muted">No clients yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** Off: no secret. Optional: signed requests are verified, unsigned ones accepted. Required: writes must be signed. */
function signingLabel(c: Client): string {
  if (!c.signingEnabled) return 'Off';
  return c.signingRequired ? 'Required' : 'Optional';
}
