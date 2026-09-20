import { useState } from 'react';
import { api, BillingPlan, Channel, CHANNELS } from '../api';
import { useLoad } from '../hooks';

interface RateForm { price: string; allowance: string }
interface PlanForm { name: string; currency: string; platformFee: string; taxPercent: string; active: boolean; rates: Record<Channel, RateForm> }

const emptyRates = (): Record<Channel, RateForm> =>
  Object.fromEntries(CHANNELS.map((c) => [c, { price: '', allowance: '0' }])) as Record<Channel, RateForm>;

const EMPTY: PlanForm = { name: '', currency: 'USD', platformFee: '0', taxPercent: '0', active: true, rates: emptyRates() };

function toForm(plan: BillingPlan): PlanForm {
  const rates = emptyRates();
  plan.rates.forEach((r) => { rates[r.channel] = { price: r.unitPrice, allowance: String(r.freeAllowance) }; });
  return { name: plan.name, currency: plan.currency, platformFee: plan.platformFee, taxPercent: String(Number((plan.taxRate * 100).toFixed(2))), active: plan.active, rates };
}

function toBody(form: PlanForm) {
  return {
    name: form.name,
    currency: form.currency,
    platformFee: Number(form.platformFee),
    taxRate: Number((Number(form.taxPercent) / 100).toFixed(4)),
    active: form.active,
    rates: CHANNELS.filter((c) => form.rates[c].price !== '').map((c) => ({
      channel: c, unitPrice: Number(form.rates[c].price), freeAllowance: Number(form.rates[c].allowance || 0),
    })),
  };
}

export default function BillingPlans() {
  const { data, error, reload } = useLoad(() => api<BillingPlan[]>('GET', '/billing/plans'));
  const [form, setForm] = useState<PlanForm>(EMPTY);
  const [editing, setEditing] = useState<string | null>(null);
  const [formError, setFormError] = useState('');

  async function save(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      await (editing ? api('PUT', `/billing/plans/${editing}`, toBody(form)) : api('POST', '/billing/plans', toBody(form)));
      setForm({ ...EMPTY, rates: emptyRates() });
      setEditing(null);
      setFormError('');
      reload();
    } catch (err) {
      setFormError((err as Error).message);
    }
  }

  const setRate = (channel: Channel, patch: Partial<RateForm>) =>
    setForm({ ...form, rates: { ...form.rates, [channel]: { ...form.rates[channel], ...patch } } });

  return (
    <>
      <h1>Billing plans</h1>
      <p className="muted">
        A plan is a price list in one currency: a price per message and a monthly free allowance per channel (allowances apply to postpaid usage),
        an optional monthly platform fee (postpaid), and a tax rate. A channel with no price is free. The currency of a plan in use cannot change.
      </p>
      <form className="card" onSubmit={save}>
        <div className="row">
          <label><span>Name</span><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
          <label><span>Currency</span>
            <input value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} maxLength={3} minLength={3} required style={{ width: 80 }} />
          </label>
          <label><span>Platform fee / month</span>
            <input type="number" min={0} step="0.01" value={form.platformFee} onChange={(e) => setForm({ ...form, platformFee: e.target.value })} style={{ width: 130 }} />
          </label>
          <label><span>Tax %</span>
            <input type="number" min={0} max={100} step="0.01" value={form.taxPercent} onChange={(e) => setForm({ ...form, taxPercent: e.target.value })} style={{ width: 90 }} />
          </label>
          <label className="check"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />Active</label>
        </div>
        <table style={{ marginTop: 12 }}>
          <thead><tr><th>Channel</th><th>Price per message</th><th>Free per month</th></tr></thead>
          <tbody>
            {CHANNELS.map((c) => (
              <tr key={c}>
                <td>{c}</td>
                <td><input type="number" min={0} step="0.000001" placeholder="free" value={form.rates[c].price} onChange={(e) => setRate(c, { price: e.target.value })} /></td>
                <td><input type="number" min={0} value={form.rates[c].allowance} onChange={(e) => setRate(c, { allowance: e.target.value })} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row" style={{ marginTop: 12 }}>
          <button type="submit" className="primary">{editing ? 'Save plan' : 'Create plan'}</button>
          {editing && <button type="button" onClick={() => { setEditing(null); setForm({ ...EMPTY, rates: emptyRates() }); }}>Cancel</button>}
          {formError && <span className="error">{formError}</span>}
        </div>
      </form>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Currency</th><th>Platform fee</th><th>Tax</th><th>Rates</th><th>Active</th><th></th></tr></thead>
          <tbody>
            {data?.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td><td>{p.currency}</td><td>{p.platformFee}</td><td>{Number((p.taxRate * 100).toFixed(2))}%</td>
                <td className="mono">{p.rates.map((r) => `${r.channel} ${r.unitPrice} (${r.freeAllowance} free)`).join(' · ')}</td>
                <td><span className={`badge ${p.active ? 'ACTIVE' : 'DISABLED'}`}>{p.active ? 'ON' : 'OFF'}</span></td>
                <td><button type="button" onClick={() => { setEditing(p.id); setForm(toForm(p)); }}>Edit</button></td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={7} className="muted">No plans yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
