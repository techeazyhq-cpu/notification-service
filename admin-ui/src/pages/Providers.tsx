import { useState } from 'react';
import { api, Channel, CHANNELS, Provider } from '../api';
import { useLoad } from '../hooks';

const EMPTY = { channel: 'EMAIL' as Channel, name: '', type: 'SMTP' as Provider['type'], enabled: true, priority: 100, settings: 'host=\nport=25\nfrom=' };

const toText = (s: Record<string, string>) => Object.entries(s).map(([k, v]) => `${k}=${v}`).join('\n');
const fromText = (t: string) =>
  Object.fromEntries(t.split('\n').map((l) => l.trim()).filter((l) => l.includes('=')).map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]));

export default function Providers() {
  const { data, error, reload } = useLoad(() => api<Provider[]>('GET', '/providers'));
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState<string | null>(null);
  const [formError, setFormError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const body = { ...form, settings: fromText(form.settings) };
    try {
      await (editing ? api('PUT', `/providers/${editing}`, body) : api('POST', '/providers', body));
      setForm(EMPTY); setEditing(null); setFormError('');
      reload();
    } catch (err) { setFormError((err as Error).message); }
  }

  const edit = (p: Provider) => { setEditing(p.id); setForm({ channel: p.channel, name: p.name, type: p.type, enabled: p.enabled, priority: p.priority, settings: toText(p.settings) }); };
  const remove = async (p: Provider) => { if (confirm(`Delete provider ${p.name}?`)) { await api('DELETE', `/providers/${p.id}`); reload(); } };
  const toggle = async (p: Provider) => { await api('PUT', `/providers/${p.id}`, { ...p, enabled: !p.enabled }); reload(); };

  return (
    <>
      <h1>Providers</h1>
      <p className="muted">Per channel, enabled providers are tried in priority order (lowest number first); the dispatcher fails over to the next on transient errors. Changes apply within ~10 seconds.</p>
      <form className="card" onSubmit={save}>
        <div className="row">
          <label>Channel
            <select value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value as Channel })}>
              {CHANNELS.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
          <label>Type
            <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as Provider['type'] })}>
              <option>SMTP</option><option>HTTP_JSON</option>
            </select>
          </label>
          <label>Priority<input type="number" value={form.priority} onChange={(e) => setForm({ ...form, priority: Number(e.target.value) })} style={{ width: 90 }} /></label>
          <label className="check"><input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />Enabled</label>
        </div>
        <label style={{ marginTop: 12 }}>Settings (key=value per line; SMTP: host, port, from, username, password, starttls, html — HTTP_JSON: url, authHeader, timeoutMs)
          <textarea value={form.settings} onChange={(e) => setForm({ ...form, settings: e.target.value })} />
        </label>
        <div className="row" style={{ marginTop: 12 }}>
          <button className="primary">{editing ? 'Save changes' : 'Add provider'}</button>
          {editing && <button type="button" onClick={() => { setEditing(null); setForm(EMPTY); }}>Cancel</button>}
          {formError && <span className="error">{formError}</span>}
        </div>
      </form>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Channel</th><th>Name</th><th>Type</th><th>Priority</th><th>Enabled</th><th>Settings</th><th></th></tr></thead>
          <tbody>
            {data?.map((p) => (
              <tr key={p.id}>
                <td>{p.channel}</td><td>{p.name}</td><td>{p.type}</td><td>{p.priority}</td>
                <td><span className={`badge ${p.enabled ? 'ACTIVE' : 'DISABLED'}`}>{p.enabled ? 'ON' : 'OFF'}</span></td>
                <td className="mono">{toText(p.settings).replaceAll('\n', ' · ')}</td>
                <td><button onClick={() => toggle(p)}>{p.enabled ? 'Disable' : 'Enable'}</button> <button onClick={() => edit(p)}>Edit</button> <button className="danger" onClick={() => remove(p)}>Delete</button></td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={7} className="muted">No providers configured — nothing can be delivered.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
