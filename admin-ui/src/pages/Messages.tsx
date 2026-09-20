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
import { api, CHANNELS, MessagePage } from '../api';
import { useLoad } from '../hooks';

const STATUSES = ['PENDING', 'QUEUED', 'PROCESSING', 'RETRYING', 'SENT', 'FAILED'];

export default function Messages() {
  const [filters, setFilters] = useState({ status: '', channel: '', recipient: '', requestId: '' });
  const [page, setPage] = useState(0);
  const query = new URLSearchParams({ page: String(page), size: '25' });
  Object.entries(filters).forEach(([k, v]) => v && query.set(k, v.trim()));

  const { data, error, reload } = useLoad(() => api<MessagePage>('GET', `/messages?${query}`), [query.toString()], 5000);

  const set = (k: keyof typeof filters, v: string) => { setFilters({ ...filters, [k]: v }); setPage(0); };
  const pages = data ? Math.max(1, Math.ceil(data.totalItems / data.size)) : 1;

  async function retry(id: string) {
    try { await api('POST', `/messages/${id}/retry`); reload(); } catch (e) { alert((e as Error).message); }
  }

  return (
    <>
      <h1>Messages</h1>
      <div className="card row">
        <label><span>Status</span>
          <select value={filters.status} onChange={(e) => set('status', e.target.value)}>
            <option value="">Any</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </label>
        <label><span>Channel</span>
          <select value={filters.channel} onChange={(e) => set('channel', e.target.value)}>
            <option value="">Any</option>{CHANNELS.map((c) => <option key={c}>{c}</option>)}
          </select>
        </label>
        <label>Recipient contains<input value={filters.recipient} onChange={(e) => set('recipient', e.target.value)} /></label>
        <label>Request ID<input value={filters.requestId} onChange={(e) => set('requestId', e.target.value)} style={{ width: 300 }} /></label>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Created</th><th>Client</th><th>Channel</th><th>Recipient</th><th>Status</th><th>Tries</th><th>Detail</th><th></th></tr></thead>
          <tbody>
            {data?.items.map((m) => (
              <tr key={m.id}>
                <td>{new Date(m.createdAt).toLocaleString()}</td>
                <td>{m.clientName}</td><td>{m.channel}</td><td className="mono">{m.recipient}</td>
                <td><span className={`badge ${m.status}`}>{m.status}</span></td>
                <td>{m.attempts}</td>
                <td className="mono" title={m.id}>{m.lastError ?? m.providerMessageId ?? ''}</td>
                <td>{m.status === 'FAILED' && <button type="button" onClick={() => retry(m.id)}>Retry</button>}</td>
              </tr>
            ))}
            {data?.items.length === 0 && <tr><td colSpan={8} className="muted">No messages match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{data?.totalItems ?? 0} message(s)</span>
          <span>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>{' '}
            Page {page + 1} of {pages}{' '}
            <button type="button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
          </span>
        </div>
      </div>
    </>
  );
}
