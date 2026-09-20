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

import { useMemo, useState } from 'react';
import { session } from '../api';
import { CATALOG, PlaygroundEndpoint } from '../playgroundCatalog';
import { buildCurl, buildUrl, formatBody, pretty, safeParse } from '../playgroundLogic';

interface Result { status: number; statusText: string; millis: number; headers: [string, string][]; body: string; failed?: string }
interface HistoryItem { id: number; label: string; status: number | string; millis: number; at: string }

const SHOWN_HEADERS = ['content-type', 'retry-after', 'content-disposition', 'idempotency-replayed'];

async function execute(endpoint: PlaygroundEndpoint, url: string, body: string, idempotencyKey: string): Promise<Result> {
  const headers: Record<string, string> = { 'X-API-Key': session.get() ?? '' };
  if (idempotencyKey.trim()) headers['Idempotency-Key'] = idempotencyKey.trim();
  let payload: BodyInit | undefined;
  if (endpoint.upload) {
    const fields = safeParse(body) as Record<string, string>;
    const form = new FormData();
    for (const [name, value] of Object.entries(fields)) {
      if (name !== 'csv' && value) form.append(name, value);
    }
    form.append('file', new Blob([fields.csv ?? ''], { type: 'text/csv' }), 'recipients.csv');
    payload = form;
  } else if (endpoint.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = body;
  }
  const started = performance.now();
  try {
    const res = await fetch(url, { method: endpoint.method, headers, body: payload });
    const text = await res.text();
    const shown = SHOWN_HEADERS.flatMap((name) => (res.headers.has(name) ? [[name, res.headers.get(name) ?? ''] as [string, string]] : []));
    return {
      status: res.status, statusText: res.statusText, millis: Math.round(performance.now() - started), headers: shown,
      body: formatBody(text, res.headers.get('content-type')),
    };
  } catch (err) {
    return { status: 0, statusText: '', millis: Math.round(performance.now() - started), headers: [], body: '', failed: (err as Error).message };
  }
}

function statusClass(status: number): string {
  if (status >= 200 && status < 300) return 'SENT';
  if (status >= 400) return 'FAILED';
  return 'PENDING';
}

export default function Playground() {
  const groups = useMemo(() => [...new Set(CATALOG.map((e) => e.group))], []);
  const [selected, setSelected] = useState<PlaygroundEndpoint>(CATALOG[0]);
  const [pathValues, setPathValues] = useState<Record<string, string>>({});
  const [queryValues, setQueryValues] = useState<Record<string, string>>({});
  const [body, setBody] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [history, setHistory] = useState<HistoryItem[]>([]);

  function choose(endpoint: PlaygroundEndpoint) {
    setSelected(endpoint);
    setPathValues({ ...(endpoint.pathParams ?? {}) });
    setQueryValues(Object.fromEntries((endpoint.query ?? []).map((q) => [q.name, q.example])));
    setBody(endpoint.body === undefined ? '' : pretty(endpoint.body));
    setIdempotencyKey('');
    setResult(null);
    setError('');
  }

  const url = buildUrl(selected, pathValues, queryValues);
  const curl = buildCurl(selected, window.location.origin, url, body, idempotencyKey);
  const missingPath = Object.entries(pathValues).filter(([, value]) => value.trim() === '').map(([name]) => name);

  async function run() {
    if (selected.body !== undefined && safeParse(body) === null) {
      setError('The body is not valid JSON');
      return;
    }
    if (missingPath.length > 0) {
      setError(`Fill in: ${missingPath.join(', ')}`);
      return;
    }
    if (selected.live && !window.confirm('This sends real messages through your account and may be billed. Continue?')) return;
    setError('');
    setBusy(true);
    const outcome = await execute(selected, url, body, idempotencyKey);
    setResult(outcome);
    setHistory((h) => [{ id: Date.now(), label: `${selected.method} ${url}`, status: outcome.failed ? 'error' : outcome.status, millis: outcome.millis, at: new Date().toLocaleTimeString() }, ...h].slice(0, 10));
    setBusy(false);
  }

  const copy = async () => {
    await navigator.clipboard?.writeText(curl);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <>
      <h1>API playground</h1>
      <p className="muted">
        Try the Client API from the browser with your own key. Requests are real: sending endpoints deliver messages and may be billed, so use your own test numbers and addresses.
        Your key is used automatically and never shown; the curl command uses <span className="mono">$API_KEY</span>.
        The complete reference is the <a href="/swagger-ui.html" target="_blank" rel="noreferrer">Swagger page</a>.
      </p>
      <div className="playground">
        <div className="card">
          {groups.map((group) => (
            <div key={group}>
              <h4>{group}</h4>
              {CATALOG.filter((e) => e.group === group).map((e) => (
                <button key={e.id} type="button" className={`link endpoint ${e.id === selected.id ? 'active' : ''}`} onClick={() => choose(e)}>
                  <span className={`method ${e.method}`}>{e.method}</span> {e.title}{e.live ? ' (live)' : ''}
                </button>
              ))}
            </div>
          ))}
        </div>

        <div>
          <div className="card">
            <h3>{selected.title}</h3>
            <p className="muted">{selected.description}</p>
            <p className="mono"><b>{selected.method}</b> {url}</p>
            {Object.keys(pathValues).length > 0 && (
              <div className="row">
                {Object.keys(pathValues).map((name) => (
                  <label key={name}><span>{name}</span>
                    <input value={pathValues[name]} onChange={(e) => setPathValues({ ...pathValues, [name]: e.target.value })} className="mono" style={{ width: 320 }} />
                  </label>
                ))}
              </div>
            )}
            {(selected.query?.length ?? 0) > 0 && (
              <div className="row">
                {selected.query?.map((q) => (
                  <label key={q.name}><span>{q.name} <span className="muted">({q.description})</span></span>
                    <input value={queryValues[q.name] ?? ''} onChange={(e) => setQueryValues({ ...queryValues, [q.name]: e.target.value })} style={{ width: 160 }} />
                  </label>
                ))}
              </div>
            )}
            {selected.idempotency && (
              <div className="row">
                <label><span>Idempotency-Key <span className="muted">(optional: retrying with the same key never sends twice)</span></span>
                  <input value={idempotencyKey} onChange={(e) => setIdempotencyKey(e.target.value)} style={{ width: 320 }} />
                </label>
              </div>
            )}
            {selected.body !== undefined && (
              <label><span>{selected.upload ? 'Form fields and CSV (JSON)' : 'Request body (JSON)'}</span>
                <textarea value={body} onChange={(e) => setBody(e.target.value)} spellCheck={false} rows={Math.min(18, body.split('\n').length + 1)} style={{ width: '100%' }} />
              </label>
            )}
            <div className="row" style={{ marginTop: 12 }}>
              <button type="button" className="primary" disabled={busy} onClick={run}>{busy ? 'Sending…' : selected.live ? 'Send (live)' : 'Send'}</button>
              <button type="button" onClick={copy}>{copied ? 'Copied' : 'Copy as curl'}</button>
              {error && <span className="error" role="alert">{error}</span>}
            </div>
            <pre className="mono playground-code">{curl}</pre>
          </div>

          {result && (
            <div className="card">
              <h3>
                Response{' '}
                {result.failed
                  ? <span className="badge FAILED">NO RESPONSE</span>
                  : <span className={`badge ${statusClass(result.status)}`}>{result.status} {result.statusText}</span>}{' '}
                <span className="muted">{result.millis} ms</span>
              </h3>
              {result.failed && <p className="error">{result.failed}</p>}
              {result.headers.length > 0 && <p className="mono muted">{result.headers.map(([k, v]) => `${k}: ${v}`).join('  ·  ')}</p>}
              <pre className="mono playground-code" aria-label="Response body">{result.body || '(empty body)'}</pre>
            </div>
          )}

          {history.length > 0 && (
            <div className="card">
              <h3>This session</h3>
              <table>
                <thead><tr><th>Time</th><th>Request</th><th>Status</th><th>ms</th></tr></thead>
                <tbody>{history.map((h) => <tr key={h.id}><td>{h.at}</td><td className="mono">{h.label}</td><td>{h.status}</td><td>{h.millis}</td></tr>)}</tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
