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
import { api, Client } from '../api';
import { useLoad } from '../hooks';

interface Retention { personalDataDays: number; deleteDays: number; idempotencyDays: number; cron: string }
interface ErasureResult { erasedMessages: number; inFlightMessages: number }

const days = (n: number) => (n > 0 ? `${n} days` : 'never');

export default function Privacy() {
  const policy = useLoad(() => api<Retention>('GET', '/privacy/retention'));
  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const [recipient, setRecipient] = useState('');
  const [clientId, setClientId] = useState('');
  const [result, setResult] = useState<ErasureResult | null>(null);
  const [error, setError] = useState('');

  async function erase(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    const scope = clientId ? clients.data?.find((c) => c.id === clientId)?.name : 'ALL clients';
    if (!window.confirm(`Erase all finished messages sent to ${recipient} for ${scope}? This cannot be undone.`)) return;
    try {
      setResult(await api<ErasureResult>('POST', '/privacy/erasure', { recipient, clientId: clientId || null }));
      setError('');
    } catch (err) {
      setResult(null);
      setError((err as Error).message);
    }
  }

  return (
    <>
      <h1>Privacy and data retention</h1>
      {policy.error && <p className="error">{policy.error}</p>}
      {policy.data && (
        <div className="card">
          <h3>Retention policy</h3>
          <p>
            Recipient, variables and error text of finished messages are erased after <b>{days(policy.data.personalDataDays)}</b>;
            message records are deleted after <b>{days(policy.data.deleteDays)}</b>; idempotency keys are cleared after <b>{days(policy.data.idempotencyDays)}</b>.
          </p>
          <p className="muted">The dispatcher runs this daily (cron <span className="mono">{policy.data.cron}</span>). Set with RETENTION_* environment variables; 0 switches a step off. Messages still in flight are never touched.</p>
        </div>
      )}
      <form className="card row" onSubmit={erase}>
        <label><span>Recipient (e-mail, phone number or device token)</span>
          <input value={recipient} onChange={(e) => setRecipient(e.target.value)} required maxLength={320} style={{ width: 320 }} />
        </label>
        <label><span>Client</span>
          <select value={clientId} onChange={(e) => setClientId(e.target.value)}>
            <option value="">All clients</option>{clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <button type="submit" className="danger" disabled={!recipient.trim()}>Erase recipient data</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {result && (
        <output className="status-block">
          Erased {result.erasedMessages} message(s).
          {result.inFlightMessages > 0 ? ` ${result.inFlightMessages} are still being delivered: repeat once they finish.` : ' Nothing left in flight.'}
        </output>
      )}
    </>
  );
}
