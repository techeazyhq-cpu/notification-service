import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { api, Me, session } from './api';
import Overview from './pages/Overview';
import RequestDetail from './pages/RequestDetail';

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
          <Route path="*" element={<Navigate to="/requests" replace />} />
        </Routes>
      </main>
    </div>
  );
}
