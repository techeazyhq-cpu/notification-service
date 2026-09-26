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
import { api, send } from '../api';
import { useLoad } from '../hooks';

interface Retention { personalDataDays: number; deleteDays: number; idempotencyDays: number }
interface ErasureResult { erasedMessages: number; inFlightMessages: number; note: string }

const days = (n: number) => (n > 0 ? `${n} days` : 'never');

export default function Privacy() {
  const { data, error } = useLoad(() => api<Retention>('/v1/privacy/retention'));
  const [recipient, setRecipient] = useState('');
  const [result, setResult] = useState<ErasureResult | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function erase(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!window.confirm(`Erase all finished messages sent to ${recipient}? This cannot be undone.`)) return;
    setBusy(true);
    setMessage('');
    try {
      setResult(await send<ErasureResult>('POST', '/v1/privacy/erasure', { recipient }));
    } catch (err) {
      setResult(null);
      setMessage((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Privacy and data retention</h1>
      {error && <p className="error">{error}</p>}
      {data && (
        <div className="card">
          <h3>How long recipient data is kept</h3>
          <ul>
            <li>Recipient address, template variables and error text of a finished message are erased after <b>{days(data.personalDataDays)}</b>.</li>
            <li>The message records themselves (status and counts, no personal data) are deleted after <b>{days(data.deleteDays)}</b>.</li>
            <li>An idempotency key protects against repeats for <b>{days(data.idempotencyDays)}</b>.</li>
          </ul>
          <p className="muted">Messages still being delivered are never erased by retention. Erased messages keep their status but cannot be sent again.</p>
        </div>
      )}
      <form className="card row" onSubmit={erase}>
        <label><span>Erase a recipient's data now (e-mail address, phone number or device token)</span>
          <input value={recipient} onChange={(e) => setRecipient(e.target.value)} required maxLength={320} style={{ width: 340 }} />
        </label>
        <button type="submit" className="danger" disabled={busy || !recipient.trim()}>Erase</button>
      </form>
      {message && <p className="error" role="alert">{message}</p>}
      {result && (
        <output className="status-block">
          Erased {result.erasedMessages} message(s). {result.inFlightMessages > 0 ? `${result.inFlightMessages} still being delivered. ` : ''}{result.note}
        </output>
      )}
    </>
  );
}
