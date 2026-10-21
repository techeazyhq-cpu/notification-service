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
import { api, DRAFTING_EFFORTS, DraftingEffort, DraftingSettings, DraftingSettingsInput } from '../api';
import { useLoad } from '../hooks';

const SUGGESTED_MODELS = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-fable-5-1'];

const EFFORT_LABELS: Record<DraftingEffort, string> = {
  low: 'Low (recommended for short templates)', medium: 'Medium', high: 'High', xhigh: 'Extra high', max: 'Max',
};

const KEY_SOURCE_LABELS: Record<DraftingSettings['apiKeySource'], string> = {
  STORED: 'saved here, encrypted', ENVIRONMENT: 'from the ANTHROPIC_API_KEY environment variable', NONE: 'none',
};

type Limits = Omit<DraftingSettingsInput, 'apiKey' | 'removeApiKey'>;

function limitsOf(settings: DraftingSettings): Limits {
  const { enabled, model, effort, maxTokens, monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute } = settings;
  return { enabled, model, effort, maxTokens, monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute };
}

/**
 * The LLM behind AI template drafting (ADR-044): on or off, the Anthropic API key, the model and how much drafting may
 * spend. The key is write-only; leaving the field empty keeps the one in effect. Changes apply within ten seconds.
 */
export default function AiDrafting() {
  const { data, error, reload } = useLoad(() => api<DraftingSettings>('GET', '/settings/template-drafting'));
  const [form, setForm] = useState<Limits | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [message, setMessage] = useState('');
  const [formError, setFormError] = useState('');

  useEffect(() => { if (data) setForm(limitsOf(data)); }, [data]);

  async function submit(settings: DraftingSettingsInput) {
    try {
      const saved = await api<DraftingSettings>('PUT', '/settings/template-drafting', settings);
      setForm(limitsOf(saved));
      setApiKey('');
      setFormError('');
      setMessage(saved.enabled ? 'Saved. Draft with AI is available on the Templates pages.' : 'Saved. AI drafting is off.');
      reload();
    } catch (err) {
      setMessage('');
      setFormError((err as Error).message);
    }
  }

  function save(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (form) submit({ ...form, apiKey: apiKey.trim() || undefined, removeApiKey: false });
  }

  function removeStoredKey() {
    if (form && confirm('Remove the stored API key? The ANTHROPIC_API_KEY environment variable, if set, applies again.')) {
      submit({ ...form, removeApiKey: true });
    }
  }

  const number = (field: keyof Limits) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(form && { ...form, [field]: Number(e.target.value) });

  return (
    <>
      <h1>AI drafting</h1>
      <p className="muted">
        The LLM that drafts SMS, e-mail and WhatsApp templates from a short description, in the template editor of this console
        and of the client tracker. Only the description and variable names are sent to Anthropic, never recipients or values.
        Changes apply within ten seconds, without a restart.
      </p>
      {error && <p className="error">{error}</p>}
      {data && form && (
        <form className="card" onSubmit={save}>
          <div className="spread">
            <h3>Status</h3>
            <span className={data.enabled ? 'badge ACTIVE' : 'badge'}>{data.enabled ? 'On' : 'Off'}</span>
          </div>
          {!data.enabled && data.apiKeySource === 'NONE' && (
            <div className="notice">Add an Anthropic API key and switch drafting on to show <b>Draft with AI</b> in the template editor.</div>
          )}
          <label className="check" style={{ marginTop: 12 }}>
            <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
            <span>Drafting is on</span>
          </label>

          <h3 style={{ marginTop: 20 }}>Anthropic API key</h3>
          <p className="muted">
            In effect: {data.apiKeyHint ? <span className="mono">{data.apiKeyHint}</span> : 'no key'} ({KEY_SOURCE_LABELS[data.apiKeySource]}).
            The key is stored encrypted and never shown again.
          </p>
          <div className="row">
            <label style={{ flex: 1 }}><span>{data.apiKeySource === 'NONE' ? 'API key' : 'Replace the key (leave empty to keep it)'}</span>
              <input type="password" autoComplete="off" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-ant-…" />
            </label>
            {data.apiKeySource === 'STORED' && (
              <button type="button" className="danger" onClick={removeStoredKey}>Remove stored key</button>
            )}
          </div>

          <h3 style={{ marginTop: 20 }}>Model</h3>
          <div className="row">
            <label><span>Model</span>
              <input list="drafting-models" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} required />
              <datalist id="drafting-models">{SUGGESTED_MODELS.map((m) => <option key={m} value={m} />)}</datalist>
            </label>
            <label><span>Effort</span>
              <select value={form.effort} onChange={(e) => setForm({ ...form, effort: e.target.value as DraftingEffort })}>
                {DRAFTING_EFFORTS.map((effort) => <option key={effort} value={effort}>{EFFORT_LABELS[effort]}</option>)}
              </select>
            </label>
            <label><span>Max tokens per reply</span>
              <input type="number" min={256} max={128000} value={form.maxTokens} onChange={number('maxTokens')} required />
            </label>
          </div>

          <h3 style={{ marginTop: 20 }}>Spending limits</h3>
          <div className="row">
            <label><span>Platform tokens per month</span>
              <input type="number" min={0} value={form.monthlyTokenBudget} onChange={number('monthlyTokenBudget')} required />
            </label>
            <label><span>Tokens per client per month (0 = admins only)</span>
              <input type="number" min={0} value={form.clientMonthlyTokenBudget} onChange={number('clientMonthlyTokenBudget')} required />
            </label>
            <label><span>Drafts per minute, per user</span>
              <input type="number" min={1} max={600} value={form.requestsPerMinute} onChange={number('requestsPerMinute')} required />
            </label>
          </div>

          <div className="row" style={{ marginTop: 16 }}>
            <button type="submit" className="primary">Save settings</button>
            {message && <span className="muted" role="status">{message}</span>}
            {formError && <span className="error" role="alert">{formError}</span>}
          </div>
          {data.updatedBy && (
            <p className="muted">Last changed by {data.updatedBy} on {new Date(data.updatedAt!).toLocaleString()}.</p>
          )}
        </form>
      )}
    </>
  );
}
