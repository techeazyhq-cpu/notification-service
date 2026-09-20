import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  api, ApiError, BillingAccount, BillingLine, BillingUsage, download, InvoiceSummary, LedgerPage, money,
} from '../api';
import { formatTime, StatusBadge } from '../components';
import { useLoad } from '../hooks';

const currentMonth = () => new Date().toISOString().slice(0, 7);

function useAccount() {
  const [account, setAccount] = useState<BillingAccount | null | undefined>();
  const [error, setError] = useState('');
  useEffect(() => {
    api<BillingAccount>('/v1/billing/account')
      .then(setAccount)
      .catch((e: ApiError) => (e.status === 404 ? setAccount(null) : setError(e.message)));
  }, []);
  return { account, error };
}

function AccountCard({ account }: Readonly<{ account: BillingAccount }>) {
  const prepaid = account.mode === 'PREPAID';
  return (
    <div className="card">
      <div className="spread">
        <h3>Plan: {account.planName}</h3>
        <span><StatusBadge status={account.mode} /> <StatusBadge status={account.status} /></span>
      </div>
      <div className="stats">
        {prepaid && <div className="stat"><span>Credit balance</span><b>{money(account.creditBalance ?? '0.00', account.currency)}</b></div>}
        {!prepaid && account.monthlySpendCap && (
          <div className="stat"><span>Monthly spend cap</span><b>{money(account.monthlySpendCap, account.currency)}</b></div>
        )}
        <div className="stat"><span>Platform fee / month</span><b>{money(account.platformFee, account.currency)}</b></div>
        <div className="stat"><span>Tax</span><b>{account.taxRate}%</b></div>
      </div>
      <table>
        <thead><tr><th>Channel</th><th>Price per message</th><th>Free per month</th></tr></thead>
        <tbody>
          {account.rates.map((r) => (
            <tr key={r.channel}><td>{r.channel}</td><td>{money(r.unitPrice, account.currency)}</td><td>{prepaid ? 'not applied (prepaid)' : r.freeAllowance}</td></tr>
          ))}
          {account.rates.length === 0 && <tr><td colSpan={3} className="muted">No channel is priced: messages are free on this plan.</td></tr>}
        </tbody>
      </table>
      <p className="muted" style={{ marginBottom: 0 }}>
        {prepaid
          ? 'Cost is reserved from your credit when a request is accepted; whatever is not sent is returned once the request finishes.'
          : 'Usage is invoiced monthly after the month ends. Only messages that were sent are charged.'}
      </p>
    </div>
  );
}

function LinesTable({ lines, currency }: Readonly<{ lines: BillingLine[]; currency: string }>) {
  return (
    <table>
      <thead><tr><th>Description</th><th>Billable</th><th>Unit price</th><th>Amount</th></tr></thead>
      <tbody>
        {lines.map((l) => (
          <tr key={`${l.kind}-${l.channel ?? 'fee'}`}>
            <td>{l.description}</td><td>{l.quantity}</td><td>{money(l.unitPrice, currency)}</td><td>{money(l.amount, currency)}</td>
          </tr>
        ))}
        {lines.length === 0 && <tr><td colSpan={4} className="muted">No messages were sent in this period.</td></tr>}
      </tbody>
    </table>
  );
}

function UsageCard({ currency }: Readonly<{ currency: string }>) {
  const [month, setMonth] = useState(currentMonth());
  const usage = useLoad(() => api<BillingUsage>(`/v1/billing/usage?month=${month}`), [month], 15000);
  const u = usage.data;
  return (
    <div className="card">
      <div className="spread">
        <h3>Usage and cost {u?.estimate && <span className="badge PROCESSING">estimate, month in progress</span>}</h3>
        <label><span>Month</span><input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
      </div>
      {usage.error && <p className="error">{usage.error}</p>}
      {u && (
        <>
          <LinesTable lines={u.lines} currency={currency} />
          <p style={{ textAlign: 'right', marginBottom: 0 }}>
            Subtotal {money(u.subtotal, currency)} · Tax {money(u.tax, currency)} · <b>Total {money(u.total, currency)}</b>
          </p>
        </>
      )}
    </div>
  );
}

function InvoicesCard() {
  const invoices = useLoad(() => api<InvoiceSummary[]>('/v1/billing/invoices'));
  const [error, setError] = useState('');
  async function exportCsv(invoice: InvoiceSummary) {
    setError('');
    try { await download(`/v1/billing/invoices/${invoice.id}/export`, `${invoice.number ?? invoice.id}.csv`); }
    catch (e) { setError((e as Error).message); }
  }
  return (
    <div className="card">
      <h3>Invoices</h3>
      {(invoices.error || error) && <p className="error">{invoices.error || error}</p>}
      <table>
        <thead><tr><th>Number</th><th>Month</th><th>Status</th><th>Total</th><th>Outstanding</th><th>Due</th><th></th></tr></thead>
        <tbody>
          {invoices.data?.map((i) => (
            <tr key={i.id}>
              <td className="mono"><Link className="plain" to={`/billing/invoices/${i.id}`}>{i.number ?? 'view'}</Link></td>
              <td>{i.month}</td><td><StatusBadge status={i.status} /></td>
              <td>{money(i.total, i.currency)}</td><td>{money(i.outstanding, i.currency)}</td>
              <td>{i.dueAt ? formatTime(i.dueAt) : ''}</td>
              <td><button type="button" onClick={() => exportCsv(i)}>CSV</button></td>
            </tr>
          ))}
          {invoices.data?.length === 0 && <tr><td colSpan={7} className="muted">No invoices yet. They appear after each month ends.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function LedgerCard() {
  const [page, setPage] = useState(0);
  const ledger = useLoad(() => api<LedgerPage>(`/v1/billing/ledger?page=${page}&size=15`), [page], 15000);
  const data = ledger.data;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  return (
    <div className="card">
      <h3>Credit ledger</h3>
      {ledger.error && <p className="error">{ledger.error}</p>}
      <table>
        <thead><tr><th>When</th><th>Type</th><th>Amount</th><th>Detail</th></tr></thead>
        <tbody>
          {data?.items.map((e) => (
            <tr key={e.id}>
              <td>{formatTime(e.createdAt)}</td><td>{e.type.replace('_', ' ')}</td>
              <td>{money(e.amount, data.currency)}</td><td>{e.description ?? e.reference}</td>
            </tr>
          ))}
          {data?.items.length === 0 && <tr><td colSpan={4} className="muted">No credit movements yet.</td></tr>}
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

export default function Billing() {
  const { account, error } = useAccount();
  if (error) return <p className="error">{error}</p>;
  if (account === undefined) return <p className="muted">Loading…</p>;
  if (account === null) {
    return (
      <>
        <h1>Billing</h1>
        <div className="card"><p style={{ margin: 0 }}>Your account is not billed: there is no plan assigned. Contact the platform administrators if you expected one.</p></div>
      </>
    );
  }
  return (
    <>
      <h1>Billing</h1>
      <AccountCard account={account} />
      <UsageCard currency={account.currency} />
      {account.mode === 'PREPAID' ? <LedgerCard /> : <InvoicesCard />}
    </>
  );
}
