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
import { api, AUDIT_OUTCOMES, AuditPage } from '../api';
import { useLoad } from '../hooks';

const PAGE_SIZE = 25;
const REFRESH_MILLISECONDS = 15000;

const toInstant = (localDateTime: string) => (localDateTime ? new Date(localDateTime).toISOString() : '');

export default function AuditLog() {
  const [filters, setFilters] = useState({ actor: '', outcome: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (filters.actor.trim()) query.set('actor', filters.actor.trim());
  if (filters.outcome) query.set('outcome', filters.outcome);
  if (filters.from) query.set('from', toInstant(filters.from));
  if (filters.to) query.set('to', toInstant(filters.to));

  const { data, error } = useLoad(() => api<AuditPage>('GET', `/audit-events?${query}`), [query.toString()],
    REFRESH_MILLISECONDS);

  const set = (key: keyof typeof filters, value: string) => { setFilters({ ...filters, [key]: value }); setPage(0); };
  const pages = data ? Math.max(1, Math.ceil(data.totalItems / data.size)) : 1;

  return (
    <>
      <h1>Audit log</h1>
      <p className="muted">
        Every change made through the admin API, including refused attempts and sign-ins. Entries cannot be edited or
        deleted. Visible to administrators only.
      </p>
      <div className="card row">
        <label>Administrator<input value={filters.actor} onChange={(e) => set('actor', e.target.value)} /></label>
        <label><span>Outcome</span>
          <select value={filters.outcome} onChange={(e) => set('outcome', e.target.value)}>
            <option value="">Any</option>{AUDIT_OUTCOMES.map((outcome) => <option key={outcome}>{outcome}</option>)}
          </select>
        </label>
        <label><span>From</span>
          <input type="datetime-local" value={filters.from} onChange={(e) => set('from', e.target.value)} />
        </label>
        <label><span>Until</span>
          <input type="datetime-local" value={filters.to} onChange={(e) => set('to', e.target.value)} />
        </label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="card">
        <table>
          <thead>
            <tr><th>When</th><th>Administrator</th><th>Role</th><th>Action</th><th>Outcome</th><th>From</th></tr>
          </thead>
          <tbody>
            {data?.items.map((event) => (
              <tr key={event.id}>
                <td>{new Date(event.occurredAt).toLocaleString()}</td>
                <td>{event.actor ?? <span className="muted">not signed in</span>}</td>
                <td>{event.actorRole ?? ''}</td>
                <td className="mono" title={event.route ?? undefined}>{event.httpMethod} {event.path}</td>
                <td><span className={`badge ${event.outcome}`}>{event.outcome}</span> {event.statusCode}</td>
                <td className="mono" title={event.userAgent ?? undefined}>{event.sourceAddress ?? ''}</td>
              </tr>
            ))}
            {data?.items.length === 0 && <tr><td colSpan={6} className="muted">No audit events match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{data?.totalItems ?? 0} event(s)</span>
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
