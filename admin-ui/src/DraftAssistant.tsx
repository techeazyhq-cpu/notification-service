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

import { ReactNode, useState } from 'react';
import { Category, Channel, DRAFT_CHANNELS, DRAFT_TONES, DraftBrief, DraftingStatus, DraftTone, TemplateDraft } from './api';
import { parseVariables, smsSummary } from './draftLogic';

const TONE_LABELS: Record<DraftTone, string> = {
  FRIENDLY: 'Friendly', PROFESSIONAL: 'Professional', FORMAL: 'Formal', REASSURING: 'Reassuring', URGENT: 'Urgent',
};

const PURPOSE_MIN_LENGTH = 10;

/**
 * Draft with AI, inside the template editor (ADR-041, ADR-044): the author describes the message and the AI writes a
 * subject and body for the channel and category chosen in the editor. The draft only fills the editor; nothing is
 * saved until the author reviews it and saves the template. It is not a form of its own, since it sits in the
 * editor's form. While drafting is off it shows {@code unavailable}, if given, and otherwise nothing.
 */
export default function DraftAssistant({ status, channel, category, request, onDraft, unavailable }: Readonly<{
  status?: DraftingStatus;
  channel: Channel;
  category: Category;
  request: (brief: DraftBrief) => Promise<TemplateDraft>;
  onDraft: (draft: TemplateDraft, brief: DraftBrief) => void;
  unavailable?: ReactNode;
}>) {
  const [open, setOpen] = useState(false);
  const [tone, setTone] = useState<DraftTone>('PROFESSIONAL');
  const [purpose, setPurpose] = useState('');
  const [variables, setVariables] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<TemplateDraft | null>(null);

  if (!status?.enabled) return unavailable ? <div className="draft-panel muted">{unavailable}</div> : null;
  const draftable = (DRAFT_CHANNELS as readonly Channel[]).includes(channel);

  async function generate() {
    if (purpose.trim().length < PURPOSE_MIN_LENGTH) {
      setError(`Describe the message in at least ${PURPOSE_MIN_LENGTH} characters.`);
      return;
    }
    setBusy(true);
    setError('');
    const brief: DraftBrief = { channel, category, tone, purpose: purpose.trim(), variables: parseVariables(variables) };
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
    <div className="draft-panel">
      <div className="spread">
        <span><b>✨ Draft with AI</b> <span className="muted">writes the subject and body below for you</span></span>
        <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}>{open ? 'Hide' : 'Describe the message'}</button>
      </div>
      {open && !draftable && <p className="muted">AI drafting covers SMS, EMAIL and WHATSAPP templates. Pick one of those channels above.</p>}
      {open && draftable && (
        <>
          <p className="muted">
            Drafting a {channel} template with {status.model}. Only this description and the variable names are sent, so leave out
            customer names, numbers and other personal data. Nothing is saved until you review the draft and save the template.
          </p>
          <div className="row">
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
            <textarea value={purpose} onChange={(e) => setPurpose(e.target.value)} maxLength={1000}
              placeholder="Tell the customer their order has shipped and that the tracking link is below." />
          </label>
          <div className="row" style={{ marginTop: 12 }}>
            <button type="button" className="primary" disabled={busy} onClick={generate}>{busy ? 'Drafting…' : 'Generate draft'}</button>
            <span className="muted">{status.tokensLeftThisMonth.toLocaleString()} tokens left this month, {status.requestsPerMinute} drafts a minute</span>
            {error && <span className="error" role="alert">{error}</span>}
          </div>
          {draft && (
            <div className="notice" style={{ marginTop: 12 }}>
              <b>Draft placed in the subject and body below.</b> Review and edit it, then save the template.
              {draft.smsLength && <div>SMS length: {smsSummary(draft.smsLength)}.</div>}
              {draft.warnings.length > 0 && <ul>{draft.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
            </div>
          )}
        </>
      )}
    </div>
  );
}
