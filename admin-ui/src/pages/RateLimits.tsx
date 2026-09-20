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
import { api, Channel, CHANNELS, Client, RateLimit } from '../api';
import { useLoad } from '../hooks';

const SCOPES = [
  { value: 'CLIENT_API', label: 'Client API calls (requests/s)', client: true, channel: false },
  { value: 'CLIENT_CHANNEL', label: 'Client delivery per channel (messages/s)', client: true, channel: true },
  { value: 'GLOBAL_CHANNEL', label: 'Platform-wide per channel (messages/s)', client: false, channel: true },
] as const;

export default function RateLimits() {
  const policies = useLoad(() => api<RateLimit[]>('GET', '/rate-limits'));
  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const [scope, setScope] = useState<RateLimit['scope']>('GLOBAL_CHANNEL');
  const [clientId, setClientId] = useState('');
  const [channel, setChannel] = useState<Channel>('EMAIL');
  const [rate, setRate] = useState(10);
  const [burst, setBurst] = useState(20);
  const [formError, setFormError] = useState('');

  const def = SCOPES.find((s) => s.value === scope)!;
  const clientName = (id?: string) => clients.data?.find((c) => c.id === id)?.name ?? id ?? '—';

  async function create(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      await api('POST', '/rate-limits', {
        scope, clientId: def.client ? clientId || null : null, channel: def.channel ? channel : null,
        ratePerSecond: rate, burst, enabled: true,
      });
      setFormError('');
      policies.reload();
    } catch (err) { setFormError((err as Error).message); }
  }

  const toggle = async (p: RateLimit) => { await api('PUT', `/rate-limits/${p.id}`, { ...p, enabled: !p.enabled }); policies.reload(); };
  const remove = async (p: RateLimit) => { if (confirm('Delete this policy?')) { await api('DELETE', `/rate-limits/${p.id}`); policies.reload(); } };

  return (
    <>
      <h1>Rate limits</h1>
      <p className="muted">Token buckets enforced in Redis: <i>rate</i> is the sustained refill per second, <i>burst</i> the bucket size. Changes apply within ~10 seconds. CLIENT_API falls back to the service default (50/s, burst 100) when no policy exists.</p>
      <form className="card row" onSubmit={create}>
        <label><span>Scope</span>
          <select value={scope} onChange={(e) => setScope(e.target.value as RateLimit['scope'])}>
            {SCOPES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </label>
        {def.client && (
          <label><span>Client</span>
            <select value={clientId} onChange={(e) => setClientId(e.target.value)} required>
              <option value="">Select…</option>
              {clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
        )}
        {def.channel && (
          <label><span>Channel</span>
            <select value={channel} onChange={(e) => setChannel(e.target.value as Channel)}>{CHANNELS.map((c) => <option key={c}>{c}</option>)}</select>
          </label>
        )}
        <label>Rate / second<input type="number" min={0.001} step="any" value={rate} onChange={(e) => setRate(Number(e.target.value))} style={{ width: 110 }} /></label>
        <label>Burst<input type="number" min={1} value={burst} onChange={(e) => setBurst(Number(e.target.value))} style={{ width: 90 }} /></label>
        <button type="submit" className="primary">Add policy</button>
        {formError && <span className="error">{formError}</span>}
      </form>
      {policies.error && <p className="error">{policies.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Scope</th><th>Client</th><th>Channel</th><th>Rate/s</th><th>Burst</th><th>Enabled</th><th></th></tr></thead>
          <tbody>
            {policies.data?.map((p) => (
              <tr key={p.id}>
                <td>{p.scope}</td><td>{p.clientId ? clientName(p.clientId) : '—'}</td><td>{p.channel ?? '—'}</td>
                <td>{p.ratePerSecond}</td><td>{p.burst}</td>
                <td><span className={`badge ${p.enabled ? 'ACTIVE' : 'DISABLED'}`}>{p.enabled ? 'ON' : 'OFF'}</span></td>
                <td><button type="button" onClick={() => toggle(p)}>{p.enabled ? 'Disable' : 'Enable'}</button> <button type="button" className="danger" onClick={() => remove(p)}>Delete</button></td>
              </tr>
            ))}
            {policies.data?.length === 0 && <tr><td colSpan={7} className="muted">No policies — only the default API limit applies.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
