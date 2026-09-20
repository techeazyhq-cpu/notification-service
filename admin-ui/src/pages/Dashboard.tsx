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
import { api, CHANNELS, Point, Summary } from '../api';
import { useLoad } from '../hooks';

const RANGES = [
  { label: 'Last hour', hours: 1, bucket: 'minute' },
  { label: 'Last 24 hours', hours: 24, bucket: 'hour' },
  { label: 'Last 7 days', hours: 168, bucket: 'day' },
];
const STATUSES = ['SENT', 'FAILED', 'RETRYING', 'PROCESSING', 'QUEUED', 'PENDING'];
const COLORS: Record<string, string> = { SENT: 'var(--ok)', FAILED: 'var(--bad)', other: 'var(--info)' };

function Chart({ points }: Readonly<{ points: Point[] }>) {
  const buckets = [...new Set(points.map((p) => p.bucket))].sort((a, b) => a.localeCompare(b));
  const series = buckets.map((b) => {
    const at = points.filter((p) => p.bucket === b);
    const sum = (f: (p: Point) => boolean) => at.filter(f).reduce((a, p) => a + p.count, 0);
    return { b, sent: sum((p) => p.status === 'SENT'), failed: sum((p) => p.status === 'FAILED'), other: sum((p) => p.status !== 'SENT' && p.status !== 'FAILED') };
  });
  if (!series.length) return <p className="muted">No messages in this range.</p>;
  const max = Math.max(...series.map((s) => s.sent + s.failed + s.other), 1);
  const W = 800, H = 160, bw = W / series.length;
  return (
    <>
      <svg className="chart" viewBox={`0 0 ${W} ${H + 4}`} preserveAspectRatio="none" role="img" aria-label="Messages over time">
        {series.map((s, i) => {
          let y = H;
          const bar = (v: number, color: string, key: string) => {
            const h = (v / max) * H;
            y -= h;
            return h > 0 ? <rect key={key} x={i * bw + 1} y={y} width={Math.max(bw - 2, 1)} height={h} fill={color} /> : null;
          };
          return (
            <g key={s.b}>
              <title>{`${new Date(s.b).toLocaleString()} — sent ${s.sent}, failed ${s.failed}, other ${s.other}`}</title>
              {bar(s.sent, COLORS.SENT, 's')}{bar(s.other, COLORS.other, 'o')}{bar(s.failed, COLORS.FAILED, 'f')}
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><i style={{ background: COLORS.SENT }} />Sent</span>
        <span><i style={{ background: COLORS.other }} />In flight / retrying</span>
        <span><i style={{ background: COLORS.FAILED }} />Failed</span>
      </div>
    </>
  );
}

export default function Dashboard() {
  const [range, setRange] = useState(RANGES[1]);
  const summary = useLoad(() => api<Summary>('GET', `/dashboard/summary?hours=${range.hours}`), [range], 5000);
  const series = useLoad(() => api<Point[]>('GET', `/dashboard/timeseries?hours=${range.hours}&bucket=${range.bucket}`), [range], 5000);

  const counts = summary.data?.counts ?? [];
  const total = (status: string) => counts.filter((c) => c.status === status).reduce((a, c) => a + c.count, 0);
  const all = counts.reduce((a, c) => a + c.count, 0);
  const count = (ch: string, st: string) => counts.find((c) => c.channel === ch && c.status === st)?.count ?? 0;

  return (
    <>
      <div className="spread">
        <h1>Dashboard</h1>
        <select value={range.label} onChange={(e) => setRange(RANGES.find((r) => r.label === e.target.value)!)}>
          {RANGES.map((r) => <option key={r.label}>{r.label}</option>)}
        </select>
      </div>
      {summary.error && <p className="error">{summary.error}</p>}
      <div className="stats">
        <div className="stat"><span>Requests</span><b>{summary.data?.requests ?? '–'}</b></div>
        <div className="stat"><span>Messages</span><b>{all}</b></div>
        <div className="stat"><span>Sent</span><b style={{ color: 'var(--ok)' }}>{total('SENT')}</b></div>
        <div className="stat"><span>Failed</span><b style={{ color: 'var(--bad)' }}>{total('FAILED')}</b></div>
        <div className="stat"><span>Backlog (all time)</span><b>{summary.data?.backlog ?? '–'}</b></div>
      </div>
      <div className="card">
        <h3>Messages over time</h3>
        <Chart points={series.data ?? []} />
      </div>
      <div className="card">
        <h3>By channel</h3>
        <table>
          <thead><tr><th>Channel</th>{STATUSES.map((s) => <th key={s}>{s}</th>)}</tr></thead>
          <tbody>
            {CHANNELS.map((ch) => (
              <tr key={ch}><td>{ch}</td>{STATUSES.map((s) => <td key={s}>{count(ch, s)}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
