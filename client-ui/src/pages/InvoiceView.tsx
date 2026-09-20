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
import { Link, useParams } from 'react-router-dom';
import { api, download, InvoiceDetail, money } from '../api';
import { formatTime, StatusBadge } from '../components';
import { useLoad } from '../hooks';

export default function InvoiceView() {
  const { invoiceId = '' } = useParams();
  const invoice = useLoad(() => api<InvoiceDetail>(`/v1/billing/invoices/${invoiceId}`), [invoiceId]);
  const [error, setError] = useState('');
  const i = invoice.data;

  async function exportCsv(current: InvoiceDetail) {
    setError('');
    try { await download(`/v1/billing/invoices/${current.id}/export`, `${current.number ?? current.id}.csv`); }
    catch (e) { setError((e as Error).message); }
  }

  if (invoice.error && !i) {
    return <><p className="crumbs no-print"><Link className="plain" to="/billing">Billing</Link></p><p className="error">{invoice.error}</p></>;
  }
  if (!i) return <p className="muted">Loading…</p>;

  return (
    <>
      <p className="crumbs no-print"><Link className="plain" to="/billing">Billing</Link> / {i.number ?? 'invoice'}</p>
      <div className="spread">
        <h1>Invoice {i.number} <StatusBadge status={i.status} /></h1>
        <span className="no-print">
          <button type="button" onClick={() => window.print()}>Print / save as PDF</button>{' '}
          <button type="button" onClick={() => exportCsv(i)}>Download CSV</button>
        </span>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <dl className="kv">
          <dt>Period</dt><dd>{i.month}</dd>
          <dt>Issued</dt><dd>{i.issuedAt ? formatTime(i.issuedAt) : ''}</dd>
          <dt>Due</dt><dd>{i.dueAt ? formatTime(i.dueAt) : ''}</dd>
          <dt>Currency</dt><dd>{i.currency}</dd>
        </dl>
      </div>
      <div className="card">
        <table>
          <thead><tr><th>Description</th><th>Billable</th><th>Unit price</th><th>Amount</th></tr></thead>
          <tbody>
            {i.lines.map((l) => (
              <tr key={`${l.kind}-${l.channel ?? 'fee'}`}>
                <td>{l.description}</td><td>{l.quantity}</td><td>{money(l.unitPrice, i.currency)}</td><td>{money(l.amount, i.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <dl className="kv" style={{ marginTop: 12, justifyContent: 'end' }}>
          <dt>Subtotal</dt><dd>{money(i.subtotal, i.currency)}</dd>
          <dt>Tax ({i.taxRate}%)</dt><dd>{money(i.tax, i.currency)}</dd>
          <dt><b>Total</b></dt><dd><b>{money(i.total, i.currency)}</b></dd>
          <dt>Paid</dt><dd>{money(i.paid, i.currency)}</dd>
          <dt><b>Outstanding</b></dt><dd><b>{money(i.outstanding, i.currency)}</b></dd>
        </dl>
      </div>
      {i.payments.length > 0 && (
        <div className="card">
          <h3>Payments received</h3>
          <table>
            <thead><tr><th>When</th><th>Amount</th><th>Method</th><th>Reference</th></tr></thead>
            <tbody>
              {i.payments.map((p) => (
                <tr key={p.reference}><td>{formatTime(p.receivedAt)}</td><td>{money(p.amount, i.currency)}</td><td>{p.method}</td><td>{p.reference}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
