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
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { api, Me, session } from './api';
import Overview from './pages/Overview';
import RequestDetail from './pages/RequestDetail';
import Templates from './pages/Templates';
import Playground from './pages/Playground';
import Billing from './pages/Billing';
import InvoiceView from './pages/InvoiceView';

function Login({ onDone }: Readonly<{ onDone: () => void }>) {
  const [apiKey, setApiKey] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    session.set(apiKey.trim());
    try {
      await api<Me>('/v1/me');
      onDone();
    } catch (err) {
      session.clear();
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="login card" onSubmit={submit}>
      <h1>Notification Tracker</h1>
      <p className="muted">Sign in with your client API key to follow your requests and their delivery status.</p>
      <label><span>API key</span>
        <input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} autoComplete="off" required />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" className="primary" disabled={busy || !apiKey.trim()}>Sign in</button>
    </form>
  );
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [ready, setReady] = useState(!session.get());

  useEffect(() => {
    if (!session.get()) return;
    api<Me>('/v1/me').then(setMe).catch(() => session.clear()).finally(() => setReady(true));
  }, []);

  useEffect(() => {
    const lost = () => setMe(null);
    window.addEventListener('auth-lost', lost);
    return () => window.removeEventListener('auth-lost', lost);
  }, []);

  if (!ready) return <p className="muted" style={{ padding: 24 }}>Loading…</p>;
  if (!me) return <Login onDone={() => api<Me>('/v1/me').then(setMe)} />;

  return (
    <div className="shell">
      <nav>
        <h2>Notification Tracker</h2>
        <Link to="/requests">Requests</Link>
        <Link to="/templates">Templates</Link>
        <Link to="/billing">Billing</Link>
        <Link to="/playground">API playground</Link>
        <p className="muted" style={{ padding: '8px 10px', fontSize: 12 }}>
          Signed in as <b>{me.name}</b><br />Channels: {me.allowedChannels.join(', ')}
        </p>
        <button type="button" className="link" onClick={() => { session.clear(); setMe(null); }}>Sign out</button>
      </nav>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/requests" replace />} />
          <Route path="/requests" element={<Overview />} />
          <Route path="/requests/:requestId" element={<RequestDetail />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/playground" element={<Playground />} />
          <Route path="/billing" element={<Billing />} />
          <Route path="/billing/invoices/:invoiceId" element={<InvoiceView />} />
          <Route path="*" element={<Navigate to="/requests" replace />} />
        </Routes>
      </main>
    </div>
  );
}
