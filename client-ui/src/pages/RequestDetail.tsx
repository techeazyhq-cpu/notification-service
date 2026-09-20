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

import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, download, inFlight, MESSAGE_STATUSES, MessageStatus, MessageView, PageView, RequestView } from '../api';
import { formatTime, ProgressBar, StatusBadge } from '../components';
import { useDebounced, useLoad } from '../hooks';

const POLL_MS = 3000;

export default function RequestDetail() {
  const { requestId = '' } = useParams();
  const [status, setStatus] = useState<'' | MessageStatus>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [live, setLive] = useState(true);
  const [downloadError, setDownloadError] = useState('');
  const recipient = useDebounced(search);

  const request = useLoad(() => api<RequestView>(`/v1/notifications/${requestId}`), [requestId], live ? POLL_MS : undefined);

  const query = new URLSearchParams({ page: String(page), size: '25' });
  if (status) query.set('status', status);
  if (recipient.trim()) query.set('recipient', recipient.trim());
  const messages = useLoad(
    () => api<PageView<MessageView>>(`/v1/notifications/${requestId}/messages?${query}`),
    [requestId, query.toString()], live ? POLL_MS : undefined);

  // Poll only while something is still moving; a finished request never changes again.
  const r = request.data;
  useEffect(() => { setLive(!r || r.status === 'PROCESSING'); }, [r]);

  async function exportCsv(only?: MessageStatus) {
    setDownloadError('');
    const query = only ? `?status=${only}` : '';
    const label = only ? `-${only.toLowerCase()}` : '';
    try {
      await download(`/v1/notifications/${requestId}/messages/export${query}`, `request-${requestId.slice(0, 8)}${label}.csv`);
    } catch (e) { setDownloadError((e as Error).message); }
  }

  if (request.error && !r) {
    return (<><p className="crumbs"><Link className="plain" to="/requests">Requests</Link></p><p className="error">{request.error}</p></>);
  }

  const pageData = messages.data;
  return (
    <>
      <p className="crumbs"><Link className="plain" to="/requests">Requests</Link> / <span className="mono">{requestId}</span></p>
      <div className="spread">
        <h1>Request {r ? <StatusBadge status={r.status} /> : null}</h1>
        {live && <span className="muted">Updating live…</span>}
      </div>

      {r && (
        <div className="card">
          <dl className="kv">
            <dt>Channel</dt><dd>{r.channel}</dd>
            <dt>Type</dt><dd>{r.kind === 'BULK' ? `Bulk, ${r.total} recipients` : 'Single'}</dd>
            <dt>Submitted</dt><dd>{formatTime(r.createdAt)}</dd>
            <dt>Your reference</dt><dd>{r.clientReference ?? '–'}</dd>
          </dl>
          <div style={{ margin: '14px 0 4px' }}><ProgressBar counts={r.counts} total={r.total} /></div>
          <div className="row" style={{ marginTop: 10 }}>
            <span><span className="badge SENT">{r.counts.sent} sent</span></span>
            <span><span className="badge FAILED">{r.counts.failed} failed</span></span>
            <span><span className="badge RETRYING">{r.counts.retrying} retrying</span></span>
            <span><span className="badge QUEUED">{r.counts.pending + r.counts.queued + r.counts.processing} waiting</span></span>
          </div>
        </div>
      )}

      <div className="card row">
        <label><span>Status</span>
          <select value={status} onChange={(e) => { setStatus(e.target.value as '' | MessageStatus); setPage(0); }}>
            <option value="">All</option>{MESSAGE_STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </label>
        <label><span>Recipient contains</span>
          <input value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
        </label>
        <button type="button" onClick={() => exportCsv()}>Download all (CSV)</button>
        {r && r.counts.failed > 0 && <button type="button" onClick={() => exportCsv('FAILED')}>Download failed (CSV)</button>}
        {downloadError && <span className="error">{downloadError}</span>}
      </div>

      {messages.error && <p className="error">{messages.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Recipient</th><th>Status</th><th>Attempts</th><th>Detail</th><th>Sent at</th></tr></thead>
          <tbody>
            {pageData?.items.map((m) => (
              <tr key={m.messageId}>
                <td className="mono">{m.recipient}</td>
                <td><StatusBadge status={m.status} /></td>
                <td>{m.attempts}</td>
                <td className="mono" title={m.messageId}>{m.lastError ?? m.providerMessageId ?? ''}</td>
                <td>{m.sentAt ? formatTime(m.sentAt) : ''}</td>
              </tr>
            ))}
            {pageData?.items.length === 0 && <tr><td colSpan={5} className="muted">No messages match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{pageData?.totalItems ?? 0} message(s){r ? `, ${inFlight(r.counts)} still in progress` : ''}</span>
          <span>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>{' '}
            Page {page + 1} of {Math.max(pageData?.totalPages ?? 1, 1)}{' '}
            <button type="button" disabled={page + 1 >= (pageData?.totalPages ?? 1)} onClick={() => setPage(page + 1)}>Next</button>
          </span>
        </div>
      </div>
    </>
  );
}
