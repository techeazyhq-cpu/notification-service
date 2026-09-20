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
import { Link, useNavigate } from 'react-router-dom';
import { api, Channel, CHANNELS, PageView, RequestView, Summary } from '../api';
import { formatTime, ProgressBar, shortId, StatusBadge } from '../components';
import { useDebounced, useLoad } from '../hooks';

const RANGES = [
  { label: 'Last hour', hours: 1 },
  { label: 'Last 24 hours', hours: 24 },
  { label: 'Last 7 days', hours: 168 },
  { label: 'Last 30 days', hours: 720 },
];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const POLL_MS = 5000;

function SummaryCards({ summary }: Readonly<{ summary?: Summary }>) {
  const total = (status: string) => (summary?.counts ?? []).filter((c) => c.status === status).reduce((a, c) => a + c.count, 0);
  const all = (summary?.counts ?? []).reduce((a, c) => a + c.count, 0);
  const sent = total('SENT');
  const failed = total('FAILED');
  const rate = sent + failed > 0 ? `${((sent / (sent + failed)) * 100).toFixed(1)}%` : '–';
  return (
    <div className="stats">
      <div className="stat"><span>Requests</span><b>{summary?.requests ?? '–'}</b></div>
      <div className="stat"><span>Messages</span><b>{summary ? all : '–'}</b></div>
      <div className="stat"><span>Sent</span><b style={{ color: 'var(--ok)' }}>{summary ? sent : '–'}</b></div>
      <div className="stat"><span>Failed</span><b style={{ color: 'var(--bad)' }}>{summary ? failed : '–'}</b></div>
      <div className="stat"><span>Success rate</span><b>{rate}</b></div>
    </div>
  );
}

export default function Overview() {
  const navigate = useNavigate();
  const [range, setRange] = useState(RANGES[1]);
  const [channel, setChannel] = useState<'' | Channel>('');
  const [reference, setReference] = useState('');
  const [lookup, setLookup] = useState('');
  const [page, setPage] = useState(0);
  const debouncedReference = useDebounced(reference);

  const query = new URLSearchParams({ page: String(page), size: '20' });
  if (channel) query.set('channel', channel);
  if (debouncedReference.trim()) query.set('clientReference', debouncedReference.trim());

  const summary = useLoad(() => api<Summary>(`/v1/notifications/summary?hours=${range.hours}`), [range], POLL_MS);
  const requests = useLoad(() => api<PageView<RequestView>>(`/v1/notifications?${query}`), [query.toString()], POLL_MS);

  function open(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (UUID.test(lookup.trim())) navigate(`/requests/${lookup.trim()}`);
  }

  const channels = CHANNELS.map((ch) => ({
    ch,
    count: (status: string) => (summary.data?.counts ?? []).filter((c) => c.channel === ch && c.status === status).reduce((a, c) => a + c.count, 0),
  }));
  const data = requests.data;

  return (
    <>
      <div className="spread">
        <h1>Requests</h1>
        <select aria-label="Summary period" value={range.label} onChange={(e) => setRange(RANGES.find((r) => r.label === e.target.value) ?? RANGES[1])}>
          {RANGES.map((r) => <option key={r.label}>{r.label}</option>)}
        </select>
      </div>
      {summary.error && <p className="error">{summary.error}</p>}
      <SummaryCards summary={summary.data} />

      <div className="card">
        <h3>By channel</h3>
        <table>
          <thead><tr><th>Channel</th><th>Sent</th><th>Failed</th><th>Retrying</th><th>Queued / processing</th></tr></thead>
          <tbody>
            {channels.map(({ ch, count }) => (
              <tr key={ch}>
                <td>{ch}</td><td>{count('SENT')}</td><td>{count('FAILED')}</td><td>{count('RETRYING')}</td>
                <td>{count('PENDING') + count('QUEUED') + count('PROCESSING')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <form className="card row" onSubmit={open}>
        <label><span>Find a request by ID</span>
          <input value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="requestId from the API response" style={{ width: 340 }} />
        </label>
        <button type="submit" disabled={!UUID.test(lookup.trim())}>Open</button>
      </form>

      <div className="card row">
        <label><span>Channel</span>
          <select value={channel} onChange={(e) => { setChannel(e.target.value as '' | Channel); setPage(0); }}>
            <option value="">Any</option>{CHANNELS.map((c) => <option key={c}>{c}</option>)}
          </select>
        </label>
        <label><span>Your reference contains</span>
          <input value={reference} onChange={(e) => { setReference(e.target.value); setPage(0); }} placeholder="clientReference" />
        </label>
      </div>

      {requests.error && <p className="error">{requests.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Submitted</th><th>Request</th><th>Channel</th><th>Type</th><th>Reference</th><th>Progress</th><th>Status</th></tr></thead>
          <tbody>
            {data?.items.map((r) => (
              <tr key={r.requestId}>
                <td>{formatTime(r.createdAt)}</td>
                <td className="mono"><Link className="plain" to={`/requests/${r.requestId}`}>{shortId(r.requestId)}</Link></td>
                <td>{r.channel}</td><td>{r.kind === 'BULK' ? `Bulk (${r.total})` : 'Single'}</td>
                <td>{r.clientReference}</td>
                <td><ProgressBar counts={r.counts} total={r.total} /></td>
                <td><StatusBadge status={r.status} /></td>
              </tr>
            ))}
            {data?.items.length === 0 && <tr><td colSpan={7} className="muted">No requests match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{data?.totalItems ?? 0} request(s)</span>
          <span>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>{' '}
            Page {page + 1} of {Math.max(data?.totalPages ?? 1, 1)}{' '}
            <button type="button" disabled={page + 1 >= (data?.totalPages ?? 1)} onClick={() => setPage(page + 1)}>Next</button>
          </span>
        </div>
      </div>
    </>
  );
}
