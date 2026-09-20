import { useState } from 'react';
import { api, Channel, CHANNELS, Client } from '../api';
import { useLoad } from '../hooks';

export default function Clients() {
  const { data, error, reload } = useLoad(() => api<Client[]>('GET', '/clients'));
  const [name, setName] = useState('');
  const [channels, setChannels] = useState<Channel[]>([...CHANNELS]);
  const [issued, setIssued] = useState<{ name: string; apiKey: string } | null>(null);
  const [formError, setFormError] = useState('');

  async function create(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await api<{ client: Client; apiKey: string }>('POST', '/clients', { name, allowedChannels: channels });
      setIssued({ name: r.client.name, apiKey: r.apiKey });
      setName('');
      setFormError('');
      reload();
    } catch (err) { setFormError((err as Error).message); }
  }

  async function rotate(c: Client) {
    if (!confirm(`Rotate the API key for ${c.name}? The old key stops working within 30 seconds.`)) return;
    const r = await api<{ client: Client; apiKey: string }>('POST', `/clients/${c.id}/rotate-key`);
    setIssued({ name: c.name, apiKey: r.apiKey });
    reload();
  }

  async function toggle(c: Client) {
    await api('PUT', `/clients/${c.id}`, { name: c.name, status: c.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE', allowedChannels: c.allowedChannels });
    reload();
  }

  const toggleChannel = (ch: Channel) => setChannels((cur) => (cur.includes(ch) ? cur.filter((x) => x !== ch) : [...cur, ch]));

  return (
    <>
      <h1>Clients</h1>
      {issued && (
        <div className="notice">
          API key for <b>{issued.name}</b> — copy it now, it is not shown again:<br />
          <span className="mono">{issued.apiKey}</span>{' '}
          <button type="button" onClick={() => navigator.clipboard.writeText(issued.apiKey)}>Copy</button>{' '}
          <button type="button" className="link" onClick={() => setIssued(null)}>Dismiss</button>
        </div>
      )}
      <form className="card row" onSubmit={create}>
        <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} /></label>
        {CHANNELS.map((ch) => (
          <label className="check" key={ch}><input type="checkbox" checked={channels.includes(ch)} onChange={() => toggleChannel(ch)} />{ch}</label>
        ))}
        <button type="submit" className="primary" disabled={!channels.length}>Create client</button>
        {formError && <span className="error">{formError}</span>}
      </form>
      {error && <p className="error">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Status</th><th>Channels</th><th>Key</th><th></th></tr></thead>
          <tbody>
            {data?.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td><span className={`badge ${c.status}`}>{c.status}</span></td>
                <td>{c.allowedChannels.join(', ')}</td>
                <td className="mono">{c.apiKeyPrefix}…</td>
                <td>
                  <button type="button" onClick={() => toggle(c)}>{c.status === 'ACTIVE' ? 'Disable' : 'Enable'}</button>{' '}
                  <button type="button" onClick={() => rotate(c)}>Rotate key</button>
                </td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td colSpan={5} className="muted">No clients yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
