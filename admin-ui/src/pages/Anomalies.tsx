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
import { Link } from 'react-router-dom';
import {
  Anomaly, ANOMALY_STATUSES, AnomalyPage, AnomalyStatus, api, CHANNELS, Client, describeAnomaly, FEATURE_LABELS,
  SEVERITIES, STATUS_LABELS,
} from '../api';
import { useLoad } from '../hooks';

const PAGE_SIZE = 25;
const REFRESH_MILLISECONDS = 30000;

function Decision({ anomaly, onDone }: Readonly<{ anomaly: Anomaly; onDone: () => void }>) {
  const [note, setNote] = useState(anomaly.resolutionNote ?? '');
  const [error, setError] = useState('');

  async function decide(status: AnomalyStatus) {
    try {
      await api('POST', `/anomalies/${anomaly.id}/status`, { status, note: note.trim() || null });
      onDone();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="row">
      <label style={{ flex: 1 }}>What was it? (kept with the decision; it tunes the thresholds)
        <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)}
          placeholder="e.g. pumping to +882; or: spring campaign, expected" />
      </label>
      <button type="button" className="danger" onClick={() => decide('CONFIRMED')}>Confirm: real</button>
      <button type="button" onClick={() => decide('FALSE_ALARM')}>False alarm</button>
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  );
}

export default function Anomalies() {
  const [filters, setFilters] = useState({ status: 'OPEN', severity: '', channel: '', clientId: '' });
  const [page, setPage] = useState(0);
  const [deciding, setDeciding] = useState<string | null>(null);
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  Object.entries(filters).forEach(([key, value]) => { if (value) query.set(key, value); });

  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const { data, error, reload } = useLoad(() => api<AnomalyPage>('GET', `/anomalies?${query}`), [query.toString()],
    REFRESH_MILLISECONDS);

  const set = (key: keyof typeof filters, value: string) => { setFilters({ ...filters, [key]: value }); setPage(0); };
  const pages = data ? Math.max(1, Math.ceil(data.totalItems / data.size)) : 1;

  return (
    <>
      <h1>Traffic anomalies</h1>
      <p className="muted">
        Hours in which a client's traffic stood far outside its own history, or reached a country it had not sent to
        before. Nothing is blocked automatically. Decide each one: your decision closes its alert, and false alarms
        with a reason are what the thresholds are tuned against.
      </p>
      <div className="card row">
        <label><span>Status</span>
          <select value={filters.status} onChange={(e) => set('status', e.target.value)}>
            <option value="">Any</option>
            {ANOMALY_STATUSES.map((status) => <option key={status} value={status}>{STATUS_LABELS[status]}</option>)}
          </select>
        </label>
        <label><span>Severity</span>
          <select value={filters.severity} onChange={(e) => set('severity', e.target.value)}>
            <option value="">Any</option>{SEVERITIES.map((severity) => <option key={severity}>{severity}</option>)}
          </select>
        </label>
        <label><span>Channel</span>
          <select value={filters.channel} onChange={(e) => set('channel', e.target.value)}>
            <option value="">Any</option>{CHANNELS.map((channel) => <option key={channel}>{channel}</option>)}
          </select>
        </label>
        <label><span>Client</span>
          <select value={filters.clientId} onChange={(e) => set('clientId', e.target.value)}>
            <option value="">Any</option>
            {clients.data?.map((client) => <option key={client.id} value={client.id}>{client.name}</option>)}
          </select>
        </label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="card">
        <table>
          <thead>
            <tr><th>Hour</th><th>Client</th><th>Channel</th><th>What</th><th>Actual</th><th>Expected</th><th>Score</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {data?.items.map((anomaly) => {
              const { actual, expected } = describeAnomaly(anomaly);
              return [
                <tr key={anomaly.id}>
                  <td>{new Date(anomaly.hour).toLocaleString()}</td>
                  <td>
                    <Link to={`/traffic/${anomaly.clientId}?channel=${anomaly.channel}`}>
                      {anomaly.clientName ?? anomaly.clientId}
                    </Link>
                  </td>
                  <td>{anomaly.channel}</td>
                  <td><span className={`badge ${anomaly.severity}`}>{anomaly.severity}</span> {FEATURE_LABELS[anomaly.feature]}</td>
                  <td>{actual}</td>
                  <td className="muted">{expected}</td>
                  <td>{anomaly.score.toFixed(1)}</td>
                  <td>
                    <span className={`badge ${anomaly.status}`}>{STATUS_LABELS[anomaly.status]}</span>
                    {anomaly.resolvedBy && (
                      <div className="muted" title={anomaly.resolutionNote ?? undefined}>
                        by {anomaly.resolvedBy}{anomaly.resolutionNote ? `: ${anomaly.resolutionNote}` : ''}
                      </div>
                    )}
                  </td>
                  <td>
                    <button type="button" onClick={() => setDeciding(deciding === anomaly.id ? null : anomaly.id)}>
                      {anomaly.status === 'OPEN' ? 'Decide' : 'Change'}
                    </button>
                  </td>
                </tr>,
                deciding === anomaly.id && (
                  <tr key={anomaly.id + '-decide'}>
                    <td colSpan={9}><Decision anomaly={anomaly} onDone={() => { setDeciding(null); reload(); }} /></td>
                  </tr>
                ),
              ];
            })}
            {data?.items.length === 0 && <tr><td colSpan={9} className="muted">No anomalies match.</td></tr>}
          </tbody>
        </table>
        <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
          <span className="muted">{data?.totalItems ?? 0} anomaly(ies)</span>
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
