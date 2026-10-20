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
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  api, Channel, CHANNELS, describeAnomaly, download, FEATURE_LABELS, STATUS_LABELS, TrafficPoint, TrafficSeries,
  WeeklyReport,
} from '../api';
import { useLoad } from '../hooks';

const RANGES = [7, 14, 30];
const REFRESH_MILLISECONDS = 60000;

function TrafficChart({ series }: Readonly<{ series: TrafficSeries }>) {
  const points = series.points;
  if (!points.some((p) => p.messages > 0)) return <p className="muted">No {series.channel} messages in this range.</p>;
  const max = Math.max(...points.map((p) => Math.max(p.messages, p.expected ?? 0)), 1);
  const W = 800, H = 180, bw = W / points.length;
  const y = (v: number) => H - (v / max) * H;
  const index = new Map(points.map((p, i) => [p.hour, i]));
  const flagged = new Map<string, string>();
  series.anomalies.forEach((a) => {
    if (a.severity === 'HIGH' || !flagged.has(a.hour)) flagged.set(a.hour, a.severity === 'HIGH' ? 'var(--bad)' : 'var(--warn)');
  });
  const segments: string[] = [];
  let current: string[] = [];
  points.forEach((p, i) => {
    if (p.expected === null) {
      if (current.length) segments.push(current.join(' '));
      current = [];
    } else {
      current.push(`${(i + 0.5) * bw},${y(p.expected)}`);
    }
  });
  if (current.length) segments.push(current.join(' '));
  const tooltip = (p: TrafficPoint) => `${new Date(p.hour).toLocaleString()}: ${p.messages} messages`
    + (p.expected === null ? '' : `, expected ${Math.round(p.expected)}`) + `, ${p.failed} failed, ${p.newRecipients} new recipients`;

  return (
    <>
      <svg className="chart" viewBox={`0 -8 ${W} ${H + 12}`} preserveAspectRatio="none" role="img"
        aria-label={`${series.channel} messages per hour against the expected baseline`}>
        {points.map((p, i) => (
          <g key={p.hour}>
            <title>{tooltip(p)}</title>
            <rect x={i * bw} y={0} width={bw} height={H} fill="transparent" />
            {p.messages > 0 && <rect x={i * bw + 0.5} y={y(p.messages)} width={Math.max(bw - 1, 0.5)} height={H - y(p.messages)} fill={flagged.get(p.hour) ?? 'var(--info)'} />}
          </g>
        ))}
        {segments.map((s) => <polyline key={s} points={s} fill="none" stroke="var(--ok)" strokeWidth={2} vectorEffect="non-scaling-stroke" />)}
        {series.anomalies.map((a) => {
          const i = index.get(a.hour);
          if (i === undefined) return null;
          const x = (i + 0.5) * bw;
          return (
            <g key={a.id}>
              <title>{`${new Date(a.hour).toLocaleString()}: ${FEATURE_LABELS[a.feature]} (${a.severity}, ${STATUS_LABELS[a.status]})`}</title>
              <line x1={x} x2={x} y1={0} y2={H} stroke={a.severity === 'HIGH' ? 'var(--bad)' : 'var(--warn)'} strokeWidth={1.5}
                strokeDasharray="4 3" vectorEffect="non-scaling-stroke" />
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><i style={{ background: 'var(--info)' }} />Messages per hour</span>
        <span><i style={{ background: 'var(--ok)' }} />Expected (same hour, past weeks; none while the client is new)</span>
        <span><i style={{ background: 'var(--bad)' }} />Hour with an anomaly (amber: warning only)</span>
      </div>
    </>
  );
}

function WeeklyReportCard({ clientId }: Readonly<{ clientId: string }>) {
  const [week, setWeek] = useState('');
  const [error, setError] = useState('');
  const query = week ? `?week=${week}` : '';
  const report = useLoad(() => api<WeeklyReport>('GET', `/traffic/${clientId}/weekly-report${query}`), [clientId, query]);

  async function exportCsv() {
    if (!report.data) return;
    try {
      await download(`/traffic/${clientId}/weekly-report/export?week=${report.data.weekStart}`,
        `traffic-${report.data.clientName}-${report.data.weekStart}.csv`);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="card">
      <div className="spread">
        <h3>Weekly report{report.data ? `: week of ${report.data.weekStart}` : ''}</h3>
        <span className="row">
          <label><span>Week (pick its Monday; empty for the last full week)</span>
            <input type="date" value={week} onChange={(e) => setWeek(e.target.value)} />
          </label>
          <button type="button" onClick={exportCsv} disabled={!report.data}>Export CSV</button>
        </span>
      </div>
      {(report.error || error) && <p className="error" role="alert">{report.error || error}</p>}
      <table>
        <thead>
          <tr><th>Channel</th><th>Messages</th><th>Failed</th><th>New recipients</th><th>Peak hour</th><th>Largest request</th><th>Top destinations</th><th>Anomalies</th></tr>
        </thead>
        <tbody>
          {report.data?.channels.map((c) => (
            <tr key={c.channel}>
              <td>{c.channel}</td>
              <td>{c.messages.toLocaleString()}</td>
              <td>{c.failed.toLocaleString()} ({(c.failureRate * 100).toFixed(1)}%)</td>
              <td>{c.newRecipients.toLocaleString()}</td>
              <td>{c.peakHour ? `${new Date(c.peakHour).toLocaleString()} (${c.peakHourMessages})` : ''}</td>
              <td>{c.largestRequest.toLocaleString()}</td>
              <td className="mono">{Object.entries(c.topDestinations).map(([d, n]) => `${d} ${n}`).join(', ')}</td>
              <td>
                {c.anomalies} ({c.high} high)
                <div className="muted">{c.confirmed} confirmed, {c.falseAlarms} false, {c.open} open</div>
              </td>
            </tr>
          ))}
          {report.data?.channels.length === 0 && <tr><td colSpan={8} className="muted">No traffic that week.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

export default function Traffic() {
  const { clientId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const channel = (params.get('channel') ?? 'SMS') as Channel;
  const days = Number(params.get('days') ?? 14);
  const series = useLoad(() => api<TrafficSeries>('GET', `/traffic/${clientId}?channel=${channel}&days=${days}`),
    [clientId, channel, days], REFRESH_MILLISECONDS);
  const choose = (key: string, value: string) => { params.set(key, value); setParams(params); };

  return (
    <>
      <div className="spread">
        <h1>Traffic: {series.data?.clientName ?? '…'}</h1>
        <span className="row">
          <select aria-label="Channel" value={channel} onChange={(e) => choose('channel', e.target.value)}>
            {CHANNELS.map((c) => <option key={c}>{c}</option>)}
          </select>
          <select aria-label="Range" value={days} onChange={(e) => choose('days', e.target.value)}>
            {RANGES.map((d) => <option key={d} value={d}>Last {d} days</option>)}
          </select>
        </span>
      </div>
      {series.error && <p className="error" role="alert">{series.error}</p>}
      <div className="card">
        <h3>{channel} messages per hour</h3>
        {series.data && <TrafficChart series={series.data} />}
      </div>
      <div className="card">
        <div className="spread">
          <h3>Anomalies in this range</h3>
          <Link to="/anomalies">All anomalies</Link>
        </div>
        <table>
          <thead><tr><th>Hour</th><th>What</th><th>Actual</th><th>Expected</th><th>Status</th></tr></thead>
          <tbody>
            {[...(series.data?.anomalies ?? [])].reverse().map((a) => {
              const { actual, expected } = describeAnomaly(a);
              return (
                <tr key={a.id}>
                  <td>{new Date(a.hour).toLocaleString()}</td>
                  <td><span className={`badge ${a.severity}`}>{a.severity}</span> {FEATURE_LABELS[a.feature]}</td>
                  <td>{actual}</td>
                  <td className="muted">{expected}</td>
                  <td><span className={`badge ${a.status}`}>{STATUS_LABELS[a.status]}</span></td>
                </tr>
              );
            })}
            {series.data?.anomalies.length === 0 && <tr><td colSpan={5} className="muted">None.</td></tr>}
          </tbody>
        </table>
      </div>
      <WeeklyReportCard clientId={clientId} />
    </>
  );
}
