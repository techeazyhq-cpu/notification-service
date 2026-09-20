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
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { api, auth } from './api';
import Dashboard from './pages/Dashboard';
import Clients from './pages/Clients';
import Templates from './pages/Templates';
import Providers from './pages/Providers';
import RateLimits from './pages/RateLimits';
import BillingPlans from './pages/BillingPlans';
import BillingAccounts from './pages/BillingAccounts';
import BillingInvoices from './pages/BillingInvoices';
import Messages from './pages/Messages';

function Login({ onDone }: Readonly<{ onDone: () => void }>) {
  const [user, setUser] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    auth.set(user, password);
    try {
      await api('GET', '/dashboard/summary?hours=1');
      onDone();
    } catch {
      auth.clear();
      setError('Invalid credentials or Admin API unreachable');
    }
  }

  return (
    <form className="login card" onSubmit={submit}>
      <h1>Notification Admin</h1>
      <label>Username<input value={user} onChange={(e) => setUser(e.target.value)} autoComplete="username" /></label>
      <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
      {error && <p className="error">{error}</p>}
      <button type="submit" className="primary">Sign in</button>
    </form>
  );
}

export default function App() {
  const [authed, setAuthed] = useState(!!auth.get());

  useEffect(() => {
    const lost = () => setAuthed(false);
    window.addEventListener('auth-lost', lost);
    return () => window.removeEventListener('auth-lost', lost);
  }, []);

  if (!authed) return <Login onDone={() => setAuthed(true)} />;

  return (
    <div className="shell">
      <nav>
        <h2>Notifications</h2>
        <NavLink to="/dashboard">Dashboard</NavLink>
        <NavLink to="/messages">Messages</NavLink>
        <NavLink to="/clients">Clients</NavLink>
        <NavLink to="/templates">Templates</NavLink>
        <NavLink to="/providers">Providers</NavLink>
        <NavLink to="/rate-limits">Rate limits</NavLink>
        <NavLink to="/billing/plans">Billing plans</NavLink>
        <NavLink to="/billing/accounts">Billing accounts</NavLink>
        <NavLink to="/billing/invoices">Invoices</NavLink>
        <button type="button" className="link" onClick={() => { auth.clear(); setAuthed(false); }}>Sign out</button>
      </nav>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/messages" element={<Messages />} />
          <Route path="/clients" element={<Clients />} />
          <Route path="/templates" element={<Templates />} />
          <Route path="/providers" element={<Providers />} />
          <Route path="/rate-limits" element={<RateLimits />} />
          <Route path="/billing/plans" element={<BillingPlans />} />
          <Route path="/billing/accounts" element={<BillingAccounts />} />
          <Route path="/billing/invoices" element={<BillingInvoices />} />
        </Routes>
      </main>
    </div>
  );
}
