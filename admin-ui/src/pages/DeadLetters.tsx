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

type Kind = 'PERMANENT' | 'EXHAUSTED' | 'DEAD_LETTERED';
interface Count { key: string; count: number }
interface Summary { total: number; byKind: Count[]; byChannel: Count[]; byClient: Count[]; topErrors: Count[]; oldest?: string }
interface Row {
  id: string; requestId: string; clientId: string; clientName: string; channel: Channel; recipient: string; kind?: Kind;
  retryable: boolean; erased: boolean; attempts: number; reprocessCount: number; lastError?: string; failedAt: string; createdAt: string;
}
interface Page { items: Row[]; page: number; size: number; totalItems: number }
interface Refusal { id: string; reason: string }
interface Result { selected: number; requeued: number; published: number; refusedCount: number; refused: Refusal[]; remaining: number }

const KINDS: Kind[] = ['EXHAUSTED', 'DEAD_LETTERED', 'PERMANENT'];
const KIND_HELP: Record<string, string> = {
  EXHAUSTED: 'Every attempt failed for a temporary reason (provider outage, timeouts). Worth reprocessing.',
  DEAD_LETTERED: 'The broker gave up delivering it to a worker. Worth reprocessing.',
  PERMANENT: 'The provider or the content rejected it (bad recipient, missing variable). Fix the cause first.',
};
const time = (iso: string) => new Date(iso).toLocaleString();

export default function DeadLetters() {
  const [clientId, setClientId] = useState('');
  const [channel, setChannel] = useState('');
  const [kind, setKind] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const query = new URLSearchParams({ page: String(page), size: '25' });
  if (clientId) query.set('clientId', clientId);
  if (channel) query.set('channel', channel);
  if (kind) query.set('kind', kind);
  if (q.trim()) query.set('q', q.trim());
  const list = useLoad(() => api<Page>('GET', `/dead-letters?${query}`), [query.toString()], 10000);
  const summary = useLoad(() => api<Summary>('GET', '/dead-letters/summary'), [], 10000);
  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const totalPages = list.data ? Math.max(1, Math.ceil(list.data.totalItems / list.data.size)) : 1;

  async function reprocess(body: Record<string, unknown>, confirmText: string) {
    if (!window.confirm(confirmText)) return;
    setBusy(true);
    setError('');
    try {
      setResult(await api<Result>('POST', '/dead-letters/reprocess', body));
      setSelected(new Set());
      list.reload();
      summary.reload();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const filters = { clientId: clientId || null, channel: channel || null, kind: kind || null, errorContains: q.trim() || null };
  const toggle = (id: string) => setSelected((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <>
      <h1>Dead letters</h1>
      <p className="muted">
        Messages that ended <b>FAILED</b>: rejected for good, out of attempts, or given up on by the broker. Reprocessing puts them back on the send path with a fresh set of attempts
        (prepaid clients are charged again for the new attempt). Messages whose data was erased cannot be reprocessed.
      </p>

      {summary.data && (
        <div className="card row">
          <div><span className="muted">Dead letters</span><br /><b style={{ fontSize: 24 }}>{summary.data.total}</b></div>
          {summary.data.byKind.map((c) => (
            <div key={c.key} title={KIND_HELP[c.key] ?? ''}><span className="muted">{c.key}</span><br /><b style={{ fontSize: 20 }}>{c.count}</b></div>
          ))}
          {summary.data.oldest && <div><span className="muted">Oldest</span><br />{time(summary.data.oldest)}</div>}
        </div>
      )}
      {summary.data && summary.data.topErrors.length > 0 && (
        <div className="card">
          <h3>Most common reasons</h3>
          <table>
            <tbody>
              {summary.data.topErrors.map((c) => (
                <tr key={c.key}>
                  <td className="mono">{c.key}</td><td>{c.count}</td>
                  <td><button type="button" onClick={() => { setQ(c.key.slice(0, 60)); setPage(0); }}>Filter</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card row">
        <label><span>Client</span>
          <select value={clientId} onChange={(e) => { setClientId(e.target.value); setPage(0); }}>
            <option value="">All</option>{clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label><span>Channel</span>
          <select value={channel} onChange={(e) => { setChannel(e.target.value); setPage(0); }}>
            <option value="">All</option>{CHANNELS.map((c) => <option key={c}>{c}</option>)}
          </select>
        </label>
        <label><span>Kind</span>
          <select value={kind} onChange={(e) => { setKind(e.target.value); setPage(0); }}>
            <option value="">All</option>{KINDS.map((k) => <option key={k}>{k}</option>)}
          </select>
        </label>
        <label><span>Error contains</span><input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} /></label>
      </div>

      <div className="card row">
        <button type="button" className="primary" disabled={busy || selected.size === 0}
          onClick={() => reprocess({ ids: [...selected] }, `Reprocess ${selected.size} selected message(s)?`)}>Reprocess selected ({selected.size})</button>
        <button type="button" disabled={busy || !list.data?.totalItems}
          onClick={() => reprocess({ ...filters, limit: 200 }, 'Reprocess up to 200 of the messages matching these filters, oldest first? Permanent failures are skipped.')}>Reprocess up to 200 matching</button>
        {result && (
          <span role="status">
            {result.requeued} re-queued, {result.published} published{result.refusedCount > 0 ? `, ${result.refusedCount} refused` : ''}; {result.remaining} still matching.
          </span>
        )}
        {error && <span className="error" role="alert">{error}</span>}
      </div>
      {result && result.refused.length > 0 && (
        <div className="card error">
          <b>Refused:</b>
          {result.refused.map((r) => <div key={r.id} className="mono">{r.id.slice(0, 8)}: {r.reason}</div>)}
        </div>
      )}

      {list.error && <p className="error">{list.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th></th><th>Failed</th><th>Client</th><th>Channel</th><th>Recipient</th><th>Kind</th><th>Attempts</th><th>Reprocessed</th><th>Error</th></tr></thead>
          <tbody>
            {list.data?.items.map((r) => (
              <tr key={r.id}>
                <td><input type="checkbox" checked={selected.has(r.id)} disabled={r.erased} onChange={() => toggle(r.id)} aria-label={`Select ${r.id}`} /></td>
                <td>{time(r.failedAt)}</td><td>{r.clientName}</td><td>{r.channel}</td><td className="mono">{r.recipient}</td>
                <td title={r.kind ? KIND_HELP[r.kind] : ''}>{r.kind ?? '-'}{!r.retryable && !r.erased ? ' (fix first)' : ''}</td>
                <td>{r.attempts}</td><td>{r.reprocessCount}</td><td>{r.lastError}</td>
              </tr>
            ))}
            {list.data?.items.length === 0 && <tr><td colSpan={9} className="muted">No dead letters match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{list.data?.totalItems ?? 0} messages</span>
          <span>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>{' '}
            Page {page + 1} of {totalPages}{' '}
            <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </span>
        </div>
      </div>
    </>
  );
}
