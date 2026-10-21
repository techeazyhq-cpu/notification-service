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
import { CATEGORIES, Category, CATEGORY_LABELS, Channel, DRAFT_CHANNELS, DRAFT_TONES, DraftBrief, DraftingStatus, DraftTone, TemplateDraft } from './api';
import { parseVariables, smsSummary } from './draftLogic';

const TONE_LABELS: Record<DraftTone, string> = {
  FRIENDLY: 'Friendly', PROFESSIONAL: 'Professional', FORMAL: 'Formal', REASSURING: 'Reassuring', URGENT: 'Urgent',
};

/**
 * Asks the AI for a template draft from a short brief (ADR-041). The draft only fills the editor: nothing is saved
 * until the author reviews it and saves the template. Rendered only when the API says drafting is on.
 */
export default function DraftAssistant({ status, channels, request, onDraft }: Readonly<{
  status: DraftingStatus;
  channels: readonly Channel[];
  request: (brief: DraftBrief) => Promise<TemplateDraft>;
  onDraft: (draft: TemplateDraft, brief: DraftBrief) => void;
}>) {
  const usable = DRAFT_CHANNELS.filter((c) => channels.includes(c));
  const [open, setOpen] = useState(false);
  const [channel, setChannel] = useState<Channel>(usable[0] ?? 'SMS');
  const [category, setCategory] = useState<Category>('TRANSACTIONAL');
  const [tone, setTone] = useState<DraftTone>('PROFESSIONAL');
  const [purpose, setPurpose] = useState('');
  const [variables, setVariables] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<TemplateDraft | null>(null);

  if (usable.length === 0) return null;
  const selected = usable.includes(channel as (typeof DRAFT_CHANNELS)[number]) ? channel : usable[0];

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError('');
    const brief: DraftBrief = { channel: selected, category, tone, purpose: purpose.trim(), variables: parseVariables(variables) };
    try {
      const result = await request(brief);
      setDraft(result);
      onDraft(result, brief);
    } catch (err) {
      setDraft(null);
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <div className="spread">
        <h3>Draft with AI</h3>
        <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? 'Hide' : 'Describe a message'}</button>
      </div>
      {open && (
        <>
          <p className="muted">
            Describe the message and the AI ({status.model}) writes a draft that meets the channel's rules. Only this description
            and the variable names are sent, so leave out customer names, numbers and other personal data. The draft fills the
            editor below; nothing is saved until you review it and save the template.
          </p>
          <div className="row">
            <label><span>Channel</span>
              <select value={selected} onChange={(e) => setChannel(e.target.value as Channel)}>
                {usable.map((c) => <option key={c}>{c}</option>)}
              </select>
            </label>
            <label><span>Category</span>
              <select value={category} onChange={(e) => setCategory(e.target.value as Category)}>
                {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>)}
              </select>
            </label>
            <label><span>Tone</span>
              <select value={tone} onChange={(e) => setTone(e.target.value as DraftTone)}>
                {DRAFT_TONES.map((t) => <option key={t} value={t}>{TONE_LABELS[t]}</option>)}
              </select>
            </label>
            <label style={{ flex: 1 }}><span>Variables it may use (comma-separated)</span>
              <input value={variables} onChange={(e) => setVariables(e.target.value)} placeholder="firstName, orderId" />
            </label>
          </div>
          <label style={{ marginTop: 12 }}>
            <span>What is the message for?</span>
            <textarea value={purpose} onChange={(e) => setPurpose(e.target.value)} required minLength={10} maxLength={1000}
              placeholder="Tell the customer their order has shipped and that the tracking link is below." />
          </label>
          <div className="row" style={{ marginTop: 12 }}>
            <button type="submit" className="primary" disabled={busy}>{busy ? 'Drafting…' : 'Draft template'}</button>
            <span className="muted">{status.tokensLeftThisMonth.toLocaleString()} tokens left this month, {status.requestsPerMinute} drafts a minute</span>
            {error && <span className="error" role="alert">{error}</span>}
          </div>
          {draft && (
            <div className="notice" style={{ marginTop: 12 }}>
              <b>Draft placed in the editor below.</b> Review and edit it, then save the template.
              {draft.smsLength && <div>SMS length: {smsSummary(draft.smsLength)}.</div>}
              {draft.warnings.length > 0 && <ul>{draft.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
            </div>
          )}
        </>
      )}
    </form>
  );
}
