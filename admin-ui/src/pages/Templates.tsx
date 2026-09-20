import { useState } from 'react';
import { api, Channel, CHANNELS, Template } from '../api';
import { useLoad } from '../hooks';

const EMPTY = { name: '', channel: 'EMAIL' as Channel, subject: '', body: '' };

export default function Templates() {
  const { data, error, reload } = useLoad(() => api<Template[]>('GET', '/templates'));
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState<string | null>(null);
  const [formError, setFormError] = useState('');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    try {
      await (editing ? api('PUT', `/templates/${editing}`, form) : api('POST', '/templates', form));
      setForm(EMPTY); setEditing(null); setFormError('');
      reload();
    } catch (err) { setFormError((err as Error).message); }
  }

  async function remove(t: Template) {
    if (!confirm(`Delete template ${t.name}?`)) return;
    try { await api('DELETE', `/templates/${t.id}`); reload(); } catch (err) { alert((err as Error).message); }
  }

  const edit = (t: Template) => { setEditing(t.id); setForm({ name: t.name, channel: t.channel, subject: t.subject ?? '', body: t.body }); };

  return (
    <>
      <h1>Templates</h1>
      <form className="card" onSubmit={save}>
        <div className="row">
          <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
          <label>Channel
            <select value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value as Channel })}>
              {CHANNELS.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label style={{ flex: 1 }}>Subject / title<input value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} /></label>
        </div>
        <label style={{ marginTop: 12 }}>Body — use {'{{variable}}'} placeholders; {'{{recipient}}'} is always available
          <textarea value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} required />
        </label>
        <div className="row" style={{ marginTop: 12 }}>
          <button className="primary">{editing ? 'Save changes' : 'Create template'}</button>
          {editing && <button type="button" onClick={() => { setEditing(null); setForm(EMPTY); }}>Cancel</button>}
          {formError && <span className="error">{formError}</span>}
        </div>
      </form>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Channel</th><th>Subject</th><th>Body</th><th></th></tr></thead>
          <tbody>
            {data?.map((t) => (
              <tr key={t.id}>
                <td>{t.name}</td><td>{t.channel}</td><td>{t.subject}</td>
                <td className="mono">{t.body.length > 80 ? t.body.slice(0, 80) + '…' : t.body}</td>
                <td><button onClick={() => edit(t)}>Edit</button> <button className="danger" onClick={() => remove(t)}>Delete</button></td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={5} className="muted">No templates yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
