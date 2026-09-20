import { useState } from 'react';
import { api, BillingInvoice, Client, download, GenerationReport, InvoiceStatus, money } from '../api';
import { useLoad } from '../hooks';

const STATUSES: InvoiceStatus[] = ['DRAFT', 'ISSUED', 'PAID', 'VOID'];

function previousMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

function InvoiceDetail({ invoice, onChange }: Readonly<{ invoice: BillingInvoice; onChange: () => void }>) {
  const [amount, setAmount] = useState(invoice.outstanding);
  const [method, setMethod] = useState('bank transfer');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  async function run(action: () => Promise<unknown>) {
    setError('');
    try {
      await action();
      onChange();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const canVoid = (invoice.status === 'DRAFT' || invoice.status === 'ISSUED') && invoice.payments.length === 0;
  return (
    <div className="card">
      <div className="spread">
        <h3>{invoice.number ?? 'Draft'} · {invoice.clientName} · {invoice.month} <span className={`badge ${invoice.status}`}>{invoice.status}</span></h3>
        <button type="button" onClick={() => run(() => download(`/billing/invoices/${invoice.id}/export`, `${invoice.number ?? invoice.id}.csv`))}>CSV</button>
      </div>
      <table>
        <thead><tr><th>Description</th><th>Billable</th><th>Unit price</th><th>Amount</th></tr></thead>
        <tbody>
          {invoice.lines.map((l) => (
            <tr key={`${l.kind}-${l.channel ?? 'fee'}`}><td>{l.description}</td><td>{l.quantity}</td><td>{l.unitPrice}</td><td>{money(l.amount, invoice.currency)}</td></tr>
          ))}
          {invoice.lines.length === 0 && <tr><td colSpan={4} className="muted">No usage and no platform fee in this period.</td></tr>}
        </tbody>
      </table>
      <p style={{ textAlign: 'right' }}>
        Subtotal {money(invoice.subtotal, invoice.currency)} · Tax {Number((invoice.taxRate * 100).toFixed(2))}% {money(invoice.tax, invoice.currency)} ·
        <b> Total {money(invoice.total, invoice.currency)}</b> · Paid {money(invoice.paid, invoice.currency)} · <b>Outstanding {money(invoice.outstanding, invoice.currency)}</b>
      </p>
      {invoice.voidReason && <p className="muted">Voided: {invoice.voidReason}</p>}
      {invoice.payments.length > 0 && (
        <table>
          <thead><tr><th>Received</th><th>Amount</th><th>Method</th><th>Reference</th></tr></thead>
          <tbody>
            {invoice.payments.map((p) => (
              <tr key={p.reference}><td>{new Date(p.receivedAt).toLocaleString()}</td><td>{money(p.amount, invoice.currency)}</td><td>{p.method}</td><td>{p.reference}</td></tr>
            ))}
          </tbody>
        </table>
      )}
      {error && <p className="error">{error}</p>}
      <div className="row" style={{ marginTop: 12 }}>
        {invoice.status === 'DRAFT' && (
          <button type="button" className="primary" onClick={() => run(() => api('POST', `/billing/invoices/${invoice.id}/issue`))}>Issue invoice</button>
        )}
        {invoice.status === 'ISSUED' && (
          <>
            <label><span>Payment amount</span><input type="number" step="0.01" min={0.01} value={amount} onChange={(e) => setAmount(e.target.value)} style={{ width: 110 }} /></label>
            <label><span>Method</span><input value={method} onChange={(e) => setMethod(e.target.value)} /></label>
            <label><span>Reference</span><input value={reference} onChange={(e) => setReference(e.target.value)} /></label>
            <button type="button" className="primary" disabled={!reference.trim()}
              onClick={() => run(() => api('POST', `/billing/invoices/${invoice.id}/payments`, { amount: Number(amount), method, reference }))}>Record payment</button>
          </>
        )}
        {canVoid && (
          <>
            <label><span>Void reason</span><input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
            <button type="button" className="danger" disabled={!reason.trim()}
              onClick={() => run(() => api('POST', `/billing/invoices/${invoice.id}/void`, { reason }))}>Void</button>
          </>
        )}
      </div>
    </div>
  );
}

export default function BillingInvoices() {
  const [status, setStatus] = useState<'' | InvoiceStatus>('');
  const [clientId, setClientId] = useState('');
  const query = new URLSearchParams();
  if (status) query.set('status', status);
  if (clientId) query.set('clientId', clientId);
  const invoices = useLoad(() => api<BillingInvoice[]>('GET', `/billing/invoices?${query}`), [query.toString()], 10000);
  const clients = useLoad(() => api<Client[]>('GET', '/clients'));
  const [month, setMonth] = useState(previousMonth());
  const [genClient, setGenClient] = useState('');
  const [report, setReport] = useState<GenerationReport | null>(null);
  const [genError, setGenError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = invoices.data?.find((i) => i.id === selectedId);

  async function generate(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      setReport(await api<GenerationReport>('POST', '/billing/invoices/generate', { month, clientId: genClient || null }));
      setGenError('');
      invoices.reload();
    } catch (err) {
      setReport(null);
      setGenError((err as Error).message);
    }
  }

  return (
    <>
      <h1>Invoices</h1>
      <p className="muted">
        Postpaid accounts are invoiced for messages that were sent, once the month is over. Generation is safe to repeat: an existing invoice is never duplicated and a draft is recalculated.
        The scheduler generates last month's drafts on the 1st; review and issue them here. Issued invoices cannot be edited.
      </p>
      <form className="card row" onSubmit={generate}>
        <label><span>Month</span><input type="month" value={month} onChange={(e) => setMonth(e.target.value)} required /></label>
        <label><span>Client (blank = all postpaid)</span>
          <select value={genClient} onChange={(e) => setGenClient(e.target.value)}>
            <option value="">All postpaid clients</option>{clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <button type="submit" className="primary">Generate invoices</button>
        {report && <span className="muted">{report.created} created, {report.existing} already existed{report.failures.length > 0 ? `, ${report.failures.length} failed` : ''}</span>}
        {genError && <span className="error">{genError}</span>}
      </form>
      {report && report.failures.length > 0 && <div className="card error">{report.failures.map((f) => <div key={f}>{f}</div>)}</div>}

      <div className="card row">
        <label><span>Status</span>
          <select value={status} onChange={(e) => setStatus(e.target.value as '' | InvoiceStatus)}>
            <option value="">Any</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </label>
        <label><span>Client</span>
          <select value={clientId} onChange={(e) => setClientId(e.target.value)}>
            <option value="">Any</option>{clients.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
      </div>
      {invoices.error && <p className="error">{invoices.error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Number</th><th>Month</th><th>Client</th><th>Status</th><th>Total</th><th>Outstanding</th><th></th></tr></thead>
          <tbody>
            {invoices.data?.map((i) => (
              <tr key={i.id}>
                <td className="mono">{i.number ?? 'draft'}</td><td>{i.month}</td><td>{i.clientName}</td>
                <td><span className={`badge ${i.status}`}>{i.status}</span></td>
                <td>{money(i.total, i.currency)}</td><td>{money(i.outstanding, i.currency)}</td>
                <td><button type="button" onClick={() => setSelectedId(i.id)}>Open</button></td>
              </tr>
            ))}
            {invoices.data?.length === 0 && <tr><td colSpan={7} className="muted">No invoices match.</td></tr>}
          </tbody>
        </table>
      </div>
      {selected && <InvoiceDetail key={selected.id + selected.status + selected.paid} invoice={selected} onChange={invoices.reload} />}
    </>
  );
}
