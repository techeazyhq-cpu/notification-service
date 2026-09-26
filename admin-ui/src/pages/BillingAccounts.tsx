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
import { api, BillingAccount, BillingPlan, Client, LedgerPage, money } from '../api';
import { useLoad } from '../hooks';

interface AccountForm { clientId: string; planId: string; mode: 'POSTPAID' | 'PREPAID'; cap: string; status: 'ACTIVE' | 'SUSPENDED'; email: string }
const EMPTY: AccountForm = { clientId: '', planId: '', mode: 'POSTPAID', cap: '', status: 'ACTIVE', email: '' };

function creditOrCap(account: BillingAccount): string {
  if (account.mode === 'PREPAID') return money(account.creditBalance ?? '0.00', account.currency);
  return account.monthlySpendCap ? `cap ${money(account.monthlySpendCap, account.currency)}` : 'no cap';
}

function CreditForm({ accounts, onDone }: Readonly<{ accounts: BillingAccount[]; onDone: () => void }>) {
  const prepaid = accounts.filter((a) => a.mode === 'PREPAID');
  const [clientId, setClientId] = useState('');
  const [kind, setKind] = useState<'TOP_UP' | 'ADJUSTMENT'>('TOP_UP');
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [description, setDescription] = useState('');
  const [message, setMessage] = useState('');
  const [isError, setIsError] = useState(false);

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await api<{ balance: string; currency: string }>('POST', `/billing/accounts/${clientId}/credit`,
        { kind, amount: Number(amount), reference, description });
      setMessage(`New balance ${money(r.balance, r.currency)}`);
      setIsError(false);
      setAmount('');
      setReference('');
      onDone();
    } catch (err) {
      setMessage((err as Error).message);
      setIsError(true);
    }
  }

  return (
    <form className="card row" onSubmit={submit}>
      <label><span>Prepaid client</span>
        <select value={clientId} onChange={(e) => setClientId(e.target.value)} required>
          <option value="">Select…</option>{prepaid.map((a) => <option key={a.clientId} value={a.clientId}>{a.clientName}</option>)}
        </select>
      </label>
      <label><span>Type</span>
        <select value={kind} onChange={(e) => setKind(e.target.value as 'TOP_UP' | 'ADJUSTMENT')}>
          <option value="TOP_UP">Top-up (payment received)</option><option value="ADJUSTMENT">Adjustment (+/-)</option>
        </select>
      </label>
      <label><span>Amount</span><input type="number" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} required style={{ width: 110 }} /></label>
      <label><span>Reference (makes it idempotent)</span><input value={reference} onChange={(e) => setReference(e.target.value)} required /></label>
      <label><span>Description</span><input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
      <button type="submit" className="primary">Apply</button>
      {message && <span className={isError ? 'error' : 'muted'}>{message}</span>}
    </form>
  );
}

function Ledger({ account }: Readonly<{ account: BillingAccount }>) {
  const [page, setPage] = useState(0);
  const ledger = useLoad(() => api<LedgerPage>('GET', `/billing/accounts/${account.clientId}/ledger?page=${page}&size=15`), [account.clientId, page]);
  const data = ledger.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  return (
    <div className="card">
      <h3>Ledger: {account.clientName}</h3>
      {ledger.error && <p className="error">{ledger.error}</p>}
      <table>
        <thead><tr><th>When</th><th>Type</th><th>Amount</th><th>Detail</th></tr></thead>
        <tbody>
          {data?.items.map((e) => (
            <tr key={e.id}><td>{new Date(e.createdAt).toLocaleString()}</td><td>{e.type}</td><td>{money(e.amount, account.currency)}</td><td>{e.description ?? e.reference}</td></tr>
          ))}
          {data?.items.length === 0 && <tr><td colSpan={4} className="muted">No entries.</td></tr>}
        </tbody>
      </table>
      <div className="spread" style={{ marginTop: 12, marginBottom: 0 }}>
        <span className="muted">{data?.total ?? 0} entries</span>
        <span>
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>{' '}
          Page {page + 1} of {pages}{' '}
          <button type="button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </span>
      </div>
    </div>
  );
}

export default function BillingAccounts() {
  const accounts = useLoad(() => api<BillingAccount[]>('GET', '/billing/accounts'), [], 10000);
  const plans = useLoad(() => api<BillingPlan[]>('GET', '/billing/plans'));
  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const [form, setForm] = useState<AccountForm>(EMPTY);
  const [formError, setFormError] = useState('');
  const [ledgerFor, setLedgerFor] = useState<BillingAccount | null>(null);

  async function save(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      await api('PUT', `/billing/accounts/${form.clientId}`, {
        planId: form.planId, mode: form.mode, status: form.status, billingEmail: form.email || null,
        monthlySpendCap: form.mode === 'POSTPAID' && form.cap !== '' ? Number(form.cap) : null,
      });
      setForm(EMPTY);
      setFormError('');
      accounts.reload();
    } catch (err) {
      setFormError((err as Error).message);
    }
  }

  const edit = (a: BillingAccount) => setForm({
    clientId: a.clientId, planId: a.planId, mode: a.mode, cap: a.monthlySpendCap ?? '', status: a.status, email: a.billingEmail ?? '',
  });

  return (
    <>
      <h1>Billing accounts</h1>
      <p className="muted">
        A client without an account is not billed. <b>Postpaid</b> clients are invoiced monthly for messages that were sent, with an optional soft monthly spend cap.{' '}
        <b> Prepaid</b> clients hold credit: the cost of a request is reserved when it is accepted and whatever is not sent is returned. Mode and currency cannot change while an account holds credit.
      </p>
      <form className="card row" onSubmit={save}>
        <label><span>Client</span>
          <select value={form.clientId} onChange={(e) => setForm({ ...form, clientId: e.target.value })} required>
            <option value="">Select…</option>{clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label><span>Plan</span>
          <select value={form.planId} onChange={(e) => setForm({ ...form, planId: e.target.value })} required>
            <option value="">Select…</option>{plans.data?.filter((p) => p.active).map((p) => <option key={p.id} value={p.id}>{p.name} ({p.currency})</option>)}
          </select>
        </label>
        <label><span>Mode</span>
          <select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as 'POSTPAID' | 'PREPAID', cap: '' })}>
            <option>POSTPAID</option><option>PREPAID</option>
          </select>
        </label>
        {form.mode === 'POSTPAID' && (
          <label><span>Monthly spend cap (optional)</span>
            <input type="number" min={0} step="0.01" value={form.cap} onChange={(e) => setForm({ ...form, cap: e.target.value })} style={{ width: 150 }} />
          </label>
        )}
        <label><span>Status</span>
          <select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as 'ACTIVE' | 'SUSPENDED' })}><option>ACTIVE</option><option>SUSPENDED</option></select>
        </label>
        <label><span>Billing email</span><input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
        <button type="submit" className="primary">Save account</button>
        {formError && <span className="error">{formError}</span>}
      </form>

      <CreditForm accounts={accounts.data ?? []} onDone={accounts.reload} />

      {accounts.error && <p className="error">{accounts.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Client</th><th>Plan</th><th>Mode</th><th>Status</th><th>Credit / cap</th><th></th></tr></thead>
          <tbody>
            {accounts.data?.map((a) => (
              <tr key={a.clientId}>
                <td>{a.clientName}</td><td>{a.planName}</td><td>{a.mode}</td>
                <td><span className={`badge ${a.status === 'ACTIVE' ? 'ACTIVE' : 'DISABLED'}`}>{a.status}</span></td>
                <td>{creditOrCap(a)}</td>
                <td>
                  <button type="button" onClick={() => edit(a)}>Edit</button>{' '}
                  {a.mode === 'PREPAID' && <button type="button" onClick={() => setLedgerFor(a)}>Ledger</button>}
                </td>
              </tr>
            ))}
            {accounts.data?.length === 0 && <tr><td colSpan={6} className="muted">No billing accounts: nobody is billed yet.</td></tr>}
          </tbody>
        </table>
      </div>
      {ledgerFor && <Ledger account={ledgerFor} />}
    </>
  );
}
