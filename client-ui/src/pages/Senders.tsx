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
import { api, send, SenderAddress } from '../api';
import { formatTime } from '../components';
import { useLoad } from '../hooks';

export default function Senders() {
  const { data, error, reload } = useLoad(() => api<SenderAddress[]>('/v1/senders'), [], 10000);
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [message, setMessage] = useState('');
  const [isError, setIsError] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await action();
      setMessage(success);
      setIsError(false);
      reload();
    } catch (err) {
      setMessage((err as Error).message);
      setIsError(true);
    } finally {
      setBusy(false);
    }
  }

  async function add(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    await run(() => send('POST', '/v1/senders', { email, displayName: displayName || null }), `A confirmation e-mail was sent to ${email}. Open the link in it to start using this address.`);
    setEmail('');
    setDisplayName('');
  }

  return (
    <>
      <h1>Sender addresses</h1>
      <p className="muted">
        E-mail can be sent from your own address instead of the platform address. Add an address and open the link we e-mail to it: only a confirmed address can be used.
        Choose a default, or pass <span className="mono">from</span> in a request to pick one. Without either, the platform address is used.
        The address must be allowed to send by your domain (SPF/DKIM), otherwise receiving mail servers may treat messages as spam.
      </p>
      <form className="card row" onSubmit={add}>
        <label><span>E-mail address</span><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={254} /></label>
        <label><span>Display name (optional)</span><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={120} /></label>
        <button type="submit" className="primary" disabled={busy}>Add and send confirmation</button>
      </form>
      {message && <p className={isError ? 'error' : 'muted'} role="status">{message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Address</th><th>Name</th><th>Status</th><th>Default</th><th>Added</th><th></th></tr></thead>
          <tbody>
            {data?.map((s) => (
              <tr key={s.id}>
                <td>{s.email}</td><td>{s.displayName ?? ''}</td>
                <td><span className={`badge ${s.status === 'VERIFIED' ? 'SENT' : 'PENDING'}`}>{s.status === 'VERIFIED' ? 'CONFIRMED' : 'WAITING FOR CONFIRMATION'}</span></td>
                <td>{s.isDefault ? 'Default' : ''}</td>
                <td>{formatTime(s.createdAt)}</td>
                <td>
                  {s.status === 'PENDING' && <button type="button" disabled={busy} onClick={() => run(() => send('POST', `/v1/senders/${s.id}/resend`), 'Confirmation e-mail sent again.')}>Resend</button>}{' '}
                  {s.status === 'VERIFIED' && !s.isDefault && <button type="button" disabled={busy} onClick={() => run(() => send('PUT', `/v1/senders/${s.id}/default`), `${s.email} is now the default sender.`)}>Make default</button>}{' '}
                  <button type="button" className="danger" disabled={busy} onClick={() => run(() => send('DELETE', `/v1/senders/${s.id}`), `${s.email} was removed.`)}>Remove</button>
                </td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={6} className="muted">No sender addresses yet: e-mail goes out from the platform address.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
