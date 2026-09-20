import { useEffect, useState } from 'react';
import { api, Channel, CHANNELS, Me, Preview, send, Template, TemplateInput, variablesOf } from '../api';
import { useDebounced, useLoad } from '../hooks';

const EMPTY: TemplateInput = { name: '', channel: 'SMS', subject: '', body: '' };

function heading(t: Template | null): string {
  if (!t) return 'New template';
  return t.readOnly ? `Shared template: ${t.name}` : `Edit ${t.name}`;
}

/** The request a client would make to send with this template, filled with the variables it needs. */
function sendExample(t: Pick<Template, 'name' | 'channel' | 'variables'>): string {
  const recipient = { EMAIL: 'user@example.com', SMS: '+14155550123', WHATSAPP: '+14155550123', PUSH: 'device-token' }[t.channel];
  const variables = Object.fromEntries(t.variables.map((v) => [v, `<${v}>`]));
  return JSON.stringify({ channel: t.channel, recipient, templateName: t.name, variables }, null, 2);
}

function TemplatePreview({ form, values, onValues }: Readonly<{
  form: TemplateInput; values: Record<string, string>; onValues: (v: Record<string, string>) => void;
}>) {
  const names = variablesOf(form.subject, form.body);
  const debounced = useDebounced({ subject: form.subject, body: form.body, values });
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!debounced.body.trim()) { setPreview(null); return; }
    let cancelled = false;
    send<Preview>('POST', '/v1/templates/preview', { subject: debounced.subject || undefined, body: debounced.body, variables: debounced.values })
      .then((p) => { if (!cancelled) { setPreview(p); setError(''); } })
      .catch((e: Error) => { if (!cancelled) setError(e.message); });
    return () => { cancelled = true; };
  }, [debounced]);

  return (
    <div className="card">
      <h3>Preview</h3>
      {names.length > 0 && (
        <div className="row" style={{ marginBottom: 12 }}>
          {names.map((n) => (
            <label key={n}><span>{n}</span>
              <input value={values[n] ?? ''} placeholder={`sample ${n}`} onChange={(e) => onValues({ ...values, [n]: e.target.value })} />
            </label>
          ))}
        </div>
      )}
      {error && <p className="error">{error}</p>}
      {preview ? (
        <>
          {preview.subject && <p><b>{preview.subject}</b></p>}
          <pre className="mono" style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{preview.body}</pre>
          {preview.missingVariables.length > 0 && (
            <p className="muted">Waiting for a sample value: {preview.missingVariables.join(', ')}. When you send, every recipient must supply these.</p>
          )}
        </>
      ) : <p className="muted">Type a body to see how it will look. <code>{'{{recipient}}'}</code> is filled in automatically.</p>}
    </div>
  );
}

export default function Templates() {
  const me = useLoad(() => api<Me>('/v1/me'));
  const { data, error, reload } = useLoad(() => api<Template[]>('/v1/templates'));
  const [form, setForm] = useState<TemplateInput>(EMPTY);
  const [editing, setEditing] = useState<Template | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const channels: readonly Channel[] = me.data?.allowedChannels ?? CHANNELS;
  const readOnly = editing?.readOnly ?? false;

  function start(t: Template | null) {
    setEditing(t);
    setFormError('');
    setValues({});
    setForm(t ? { name: t.name, channel: t.channel, subject: t.subject ?? '', body: t.body } : { ...EMPTY, channel: channels[0] ?? 'SMS' });
  }

  async function save(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    const input = { ...form, subject: form.subject?.trim() ? form.subject : undefined };
    try {
      const saved = editing
        ? await send<Template>('PUT', `/v1/templates/${editing.id}`, input)
        : await send<Template>('POST', '/v1/templates', input);
      setEditing(saved);
      setFormError('');
      reload();
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function remove(t: Template) {
    if (!confirm(`Delete template "${t.name}"? Requests you already sent are not affected.`)) return;
    try {
      await send('DELETE', `/v1/templates/${t.id}`);
      if (editing?.id === t.id) start(null);
      reload();
    } catch (err) {
      setFormError((err as Error).message);
    }
  }

  const example = sendExample({ name: form.name || 'my-template', channel: form.channel, variables: variablesOf(form.subject, form.body) });

  return (
    <>
      <div className="spread">
        <h1>Templates</h1>
        <button type="button" onClick={() => start(null)}>New template</button>
      </div>
      <p className="muted">
        Your own templates can be edited and deleted; shared templates from the platform are read-only, but you can copy them.
        Editing a template only affects requests you send afterwards: requests already accepted keep the text they were sent with.
      </p>

      <form className="card" onSubmit={save}>
        <h3>{heading(editing)}</h3>
        <div className="row">
          <label><span>Name</span>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required minLength={2} maxLength={64}
              pattern="[A-Za-z0-9][A-Za-z0-9._\-]{1,63}" title="Letters, digits, dot, dash or underscore" disabled={readOnly} />
          </label>
          <label><span>Channel</span>
            <select value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value as Channel })} disabled={readOnly || !!editing}>
              {CHANNELS.filter((c) => channels.includes(c)).map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label style={{ flex: 1 }}><span>Subject / title (required for EMAIL)</span>
            <input value={form.subject ?? ''} onChange={(e) => setForm({ ...form, subject: e.target.value })} maxLength={500} disabled={readOnly} />
          </label>
        </div>
        <label style={{ marginTop: 12 }}>
          <span>Body: use <code>{'{{variable}}'}</code> for values you supply per recipient</span>
          <textarea value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} required maxLength={10000} disabled={readOnly} />
        </label>
        <div className="row" style={{ marginTop: 12 }}>
          {!readOnly && <button type="submit" className="primary" disabled={busy}>{editing ? 'Save changes' : 'Create template'}</button>}
          {readOnly && editing && (
            <button type="button" onClick={() => { setEditing(null); setForm({ ...form, name: `${form.name}-copy` }); }}>
              Copy as my own template
            </button>
          )}
          {formError && <span className="error" role="alert">{formError}</span>}
        </div>
      </form>

      <TemplatePreview form={form} values={values} onValues={setValues} />

      <div className="card">
        <h3>Send with this template</h3>
        <pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{`POST /v1/notifications\n${example}`}</pre>
      </div>

      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Channel</th><th>Owner</th><th>Variables</th><th>Updated</th><th></th></tr></thead>
          <tbody>
            {data?.map((t) => (
              <tr key={t.id}>
                <td>{t.name}</td>
                <td>{t.channel}</td>
                <td><span className={t.scope === 'OWNED' ? 'badge ACTIVE' : 'badge'}>{t.scope === 'OWNED' ? 'Yours' : 'Shared'}</span></td>
                <td className="mono">{t.variables.join(', ')}</td>
                <td>{new Date(t.updatedAt).toLocaleDateString()}</td>
                <td>
                  <button type="button" onClick={() => start(t)}>{t.readOnly ? 'View' : 'Edit'}</button>{' '}
                  {!t.readOnly && <button type="button" className="danger" onClick={() => remove(t)}>Delete</button>}
                </td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={6} className="muted">No templates yet. Create your first one above.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
