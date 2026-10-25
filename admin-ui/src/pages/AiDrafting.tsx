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
import {
  api, ClientDrafting, ClientDraftingInput, DRAFTING_EFFORTS, DraftingEffort, DraftingSettings, DraftingSettingsInput,
  LLM_PROVIDERS, LlmProvider,
} from '../api';
import { useLoad } from '../hooks';

interface ProviderGuide {
  label: string;
  defaultEndpoint?: string;
  keyRequired: boolean;
  modelExample: string;
  note?: string;
}

/** What the page tells an administrator about each provider; the server holds the same defaults. */
const PROVIDERS: Record<LlmProvider, ProviderGuide> = {
  ANTHROPIC: { label: 'Anthropic', defaultEndpoint: 'https://api.anthropic.com', keyRequired: true, modelExample: 'claude-opus-5-5' },
  OPENAI: { label: 'OpenAI', defaultEndpoint: 'https://api.openai.com/v1', keyRequired: true, modelExample: 'the model name from your OpenAI account' },
  GEMINI: {
    label: 'Google Gemini', defaultEndpoint: 'https://generativelanguage.googleapis.com/v1beta/openai', keyRequired: true,
    modelExample: 'the Gemini model name, as listed by Google AI Studio',
  },
  DEEPSEEK: { label: 'DeepSeek', defaultEndpoint: 'https://api.deepseek.com', keyRequired: true, modelExample: 'deepseek-chat' },
  OLLAMA: {
    label: 'Ollama (local)', defaultEndpoint: 'http://localhost:11434/v1', keyRequired: false, modelExample: 'llama3.1:8b',
    note: 'Running in Docker Compose, use http://host.docker.internal:11434/v1. A local or plain-HTTP host must be listed in PROVIDER_TRUSTED_HOSTS.',
  },
  OPENAI_COMPATIBLE: {
    label: 'Other OpenAI-compatible API', keyRequired: false, modelExample: 'the model name the server lists',
    note: 'Any server that offers /chat/completions, such as vLLM, LM Studio or an LLM gateway. Give its base URL, ending before /chat/completions.',
  },
};

const ANTHROPIC_MODELS = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-fable-5-1'];

const EFFORT_LABELS: Record<DraftingEffort, string> = {
  low: 'Low (recommended for short templates)', medium: 'Medium', high: 'High', xhigh: 'Extra high', max: 'Max',
};

const KEY_SOURCE_LABELS: Record<DraftingSettings['apiKeySource'], string> = {
  STORED: 'saved here, encrypted', ENVIRONMENT: 'from the service environment', NONE: 'none',
};

type Limits = Omit<DraftingSettingsInput, 'apiKey' | 'removeApiKey'>;

function limitsOf(settings: DraftingSettings): Limits {
  const { enabled, provider, baseUrl, model, effort, maxTokens, monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute } = settings;
  return { enabled, provider, baseUrl: baseUrl ?? '', model, effort, maxTokens, monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute };
}

/**
 * The LLM behind AI template drafting (ADR-044): on or off, which provider (Anthropic, OpenAI, Gemini, DeepSeek, a
 * local Ollama or any OpenAI-compatible server) at which endpoint, its API key, the model and how much drafting may
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
      setMessage(saved.enabled ? 'Saved. Draft with AI is available in the template editor.' : 'Saved. AI drafting is off.');
      reload();
    } catch (err) {
      setMessage('');
      setFormError((err as Error).message);
    }
  }

  function save(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (form) submit({ ...form, baseUrl: form.baseUrl?.trim() || undefined, apiKey: apiKey.trim() || undefined, removeApiKey: false });
  }

  function removeStoredKey() {
    if (form && confirm('Remove the stored API key? A key set in the service environment, if any, applies again.')) {
      submit({ ...form, baseUrl: form.baseUrl?.trim() || undefined, removeApiKey: true });
    }
  }

  function chooseProvider(provider: LlmProvider) {
    if (!form) return;
    const sameProvider = provider === data?.provider;
    setForm({ ...form, provider, baseUrl: sameProvider ? data?.baseUrl ?? '' : '', model: sameProvider ? data!.model : '' });
  }

  const number = (field: keyof Limits) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(form && { ...form, [field]: Number(e.target.value) });

  const guide = form ? PROVIDERS[form.provider] : undefined;
  const keyChangesWithProvider = data && form && form.provider !== data.provider;

  return (
    <>
      <h1>AI drafting</h1>
      <p className="muted">
        The LLM that drafts SMS, e-mail, WhatsApp and push templates from a short description, in the template editor of this console
        and of the client tracker. Any provider works: Anthropic, OpenAI, Google Gemini, DeepSeek, a local Ollama or another
        OpenAI-compatible server. Only the description and variable names are sent, never recipients or values. Changes apply
        within ten seconds, without a restart.
      </p>
      {error && <p className="error">{error}</p>}
      {data && form && guide && (
        <form className="card" onSubmit={save}>
          <div className="spread">
            <h3>Status</h3>
            <span className={data.enabled ? 'badge ACTIVE' : 'badge'}>{data.enabled ? 'On' : 'Off'}</span>
          </div>
          {data.enabled && <p className="muted">Drafting with <b>{data.model}</b> from {PROVIDERS[data.provider].label} at <span className="mono">{data.endpoint}</span>.</p>}
          {!data.enabled && <div className="notice">Choose a provider and model, add its API key if it needs one, and switch drafting on to show <b>Draft with AI</b> in the template editor.</div>}
          <label className="check" style={{ marginTop: 12 }}>
            <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
            <span>Drafting is on</span>
          </label>

          <h3 style={{ marginTop: 20 }}>Provider</h3>
          <div className="row">
            <label><span>LLM provider</span>
              <select value={form.provider} onChange={(e) => chooseProvider(e.target.value as LlmProvider)}>
                {LLM_PROVIDERS.map((p) => <option key={p} value={p}>{PROVIDERS[p].label}</option>)}
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>{guide.defaultEndpoint ? 'Base URL (leave empty for the default)' : 'Base URL (required)'}</span>
              <input value={form.baseUrl ?? ''} onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
                placeholder={guide.defaultEndpoint ?? 'https://llm.example.com/v1'} required={!guide.defaultEndpoint} />
            </label>
          </div>
          {guide.note && <p className="muted">{guide.note}</p>}

          <h3 style={{ marginTop: 20 }}>API key</h3>
          <p className="muted">
            In effect: {data.apiKeyHint ? <span className="mono">{data.apiKeyHint}</span> : 'no key'} ({KEY_SOURCE_LABELS[data.apiKeySource]}).
            {guide.keyRequired ? ` ${guide.label} needs a key.` : ` ${guide.label} may run without one.`} The key is stored encrypted and never shown again.
            {keyChangesWithProvider && data.apiKeyHint && ' You switched provider: enter the new provider\'s key, or remove the stored one.'}
          </p>
          <div className="row">
            <label style={{ flex: 1 }}><span>{data.apiKeySource === 'NONE' ? 'API key' : 'Replace the key (leave empty to keep it)'}</span>
              <input type="password" autoComplete="off" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="Paste the provider's API key" />
            </label>
            {data.apiKeySource === 'STORED' && (
              <button type="button" className="danger" onClick={removeStoredKey}>Remove stored key</button>
            )}
          </div>

          <h3 style={{ marginTop: 20 }}>Model</h3>
          <div className="row">
            <label style={{ flex: 1 }}><span>Model</span>
              <input list={form.provider === 'ANTHROPIC' ? 'anthropic-models' : undefined} value={form.model}
                onChange={(e) => setForm({ ...form, model: e.target.value })} placeholder={guide.modelExample} required />
              <datalist id="anthropic-models">{ANTHROPIC_MODELS.map((m) => <option key={m} value={m} />)}</datalist>
            </label>
            {form.provider === 'ANTHROPIC' && (
              <label><span>Effort</span>
                <select value={form.effort} onChange={(e) => setForm({ ...form, effort: e.target.value as DraftingEffort })}>
                  {DRAFTING_EFFORTS.map((effort) => <option key={effort} value={effort}>{EFFORT_LABELS[effort]}</option>)}
                </select>
              </label>
            )}
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
      {data && <ClientAccess defaultBudget={data.clientMonthlyTokenBudget} settingsVersion={data.updatedAt} />}
    </>
  );
}

/**
 * Each client's drafting policy (ADR-045): switch drafting off for one client, or give it its own monthly budget in
 * place of the default, with what it has spent this month. A change applies to the client's next draft.
 */
function ClientAccess({ defaultBudget, settingsVersion }: { defaultBudget: number; settingsVersion?: string }) {
  const { data, error, reload } = useLoad(() => api<ClientDrafting[]>('GET', '/settings/template-drafting/clients'),
    [settingsVersion]);

  return (
    <div className="card">
      <h3>Clients</h3>
      <p className="muted">
        Every client has drafting with the default of {defaultBudget.toLocaleString()} tokens a month unless you
        change it here. Leave the budget empty for the default, or set a client's own; 0 or switching it off stops
        that client drafting. Spend counts from the first of the month (UTC).
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>Client</th><th>Drafting</th><th>Own monthly budget</th><th>In effect</th><th>Used this month</th><th></th>
          </tr>
        </thead>
        <tbody>
          {data?.map((client) => <ClientAccessRow key={client.clientId} client={client} onSaved={reload} />)}
          {data?.length === 0 && <tr><td colSpan={6} className="muted">No clients yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function ClientAccessRow({ client, onSaved }: { client: ClientDrafting; onSaved: () => void }) {
  const [enabled, setEnabled] = useState(client.draftingEnabled);
  const [budget, setBudget] = useState(client.monthlyTokenBudget?.toString() ?? '');
  const [rowError, setRowError] = useState('');

  useEffect(() => {
    setEnabled(client.draftingEnabled);
    setBudget(client.monthlyTokenBudget?.toString() ?? '');
  }, [client]);

  const changed = enabled !== client.draftingEnabled || budget !== (client.monthlyTokenBudget?.toString() ?? '');
  const usedPercent = client.effectiveMonthlyTokenBudget > 0
    ? Math.min(100, Math.round((100 * client.tokensUsedThisMonth) / client.effectiveMonthlyTokenBudget))
    : undefined;
  const used = client.tokensUsedThisMonth.toLocaleString() + (usedPercent === undefined ? '' : ` (${usedPercent}%)`);

  async function save() {
    const input: ClientDraftingInput = {
      draftingEnabled: enabled, monthlyTokenBudget: budget.trim() === '' ? undefined : Number(budget),
    };
    try {
      await api<ClientDrafting>('PUT', `/settings/template-drafting/clients/${client.clientId}`, input);
      setRowError('');
      onSaved();
    } catch (err) { setRowError((err as Error).message); }
  }

  return (
    <tr>
      <td>
        {client.clientName} {client.clientStatus === 'DISABLED' && <span className="badge DISABLED">DISABLED</span>}
      </td>
      <td>
        <label className="check">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)}
            aria-label={`Drafting for ${client.clientName}`} />
          <span>{enabled ? 'On' : 'Off'}</span>
        </label>
      </td>
      <td>
        <input type="number" min={0} value={budget} onChange={(e) => setBudget(e.target.value)} placeholder="Default"
          aria-label={`Own monthly budget for ${client.clientName}`} style={{ width: 130 }} />
      </td>
      <td>
        {client.effectiveMonthlyTokenBudget > 0
          ? client.effectiveMonthlyTokenBudget.toLocaleString()
          : <span className="muted">No drafting</span>}
      </td>
      <td>{used}</td>
      <td>
        <button type="button" onClick={save} disabled={!changed}>Save</button>
        {rowError && <span className="error" role="alert"> {rowError}</span>}
      </td>
    </tr>
  );
}
