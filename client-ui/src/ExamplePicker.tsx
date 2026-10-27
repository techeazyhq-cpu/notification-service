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
import { Category, CATEGORY_LABELS, Channel, TemplateExample } from './api';

/** The languages the examples come in (ADR-046), each named in its own script and in English. */
const LANGUAGE_NAMES: Record<string, string> = {
  en: 'English',
  hi: 'हिन्दी · Hindi',
  ta: 'தமிழ் · Tamil',
  ar: 'العربية · Arabic',
};

/**
 * Start from an example, inside the template editor (ADR-046): ready-made templates for the editor's channel and
 * category, which meet the channel's rules as written. Picking one fills the subject and body; nothing is saved until
 * the author saves the template. It needs no model, so it is shown whether AI drafting is on or off. Translations of
 * an example share its name, so switching language keeps the same example chosen.
 */
export default function ExamplePicker({ examples, channel, category, hasContent, onPick }: Readonly<{
  examples?: TemplateExample[];
  channel: Channel;
  category: Category;
  hasContent: boolean;
  onPick: (example: TemplateExample) => void;
}>) {
  const [chosenId, setChosenId] = useState('');
  const [language, setLanguage] = useState('en');
  const forChannel = (examples ?? []).filter((e) => e.channel === channel && e.category === category);
  const languages = [...new Set(forChannel.map((e) => e.language))];
  if (forChannel.length === 0) return null;
  const shown = languages.includes(language) ? language : languages[0];
  const matching = forChannel.filter((e) => e.language === shown);
  const chosen = matching.find((e) => baseId(e) === chosenId) ?? matching[0];

  function use() {
    if (hasContent && !confirm(`Replace the subject and body with the example "${chosen.name}"?`)) return;
    onPick(chosen);
  }

  return (
    <div className="draft-panel">
      <div className="row">
        <label style={{ flex: 1 }}>
          <span>
            <b>Start from an example</b>{' '}
            <span className="muted">{matching.length} for {channel}, {CATEGORY_LABELS[category].toLowerCase()}</span>
          </span>
          <select value={baseId(chosen)} onChange={(e) => setChosenId(e.target.value)}>
            {matching.map((e) => <option key={e.id} value={baseId(e)}>{e.name}</option>)}
          </select>
        </label>
        {languages.length > 1 && (
          <label>
            <span>Language</span>
            <select value={shown} onChange={(e) => setLanguage(e.target.value)}>
              {languages.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l] ?? l}</option>)}
            </select>
          </label>
        )}
        <button type="button" onClick={use}>Use this example</button>
      </div>
      <p className="muted" style={{ marginBottom: 0 }}>
        {chosen.description}
        {chosen.variables.length > 0 && <> Uses {chosen.variables.map((v) => `{{${v}}}`).join(', ')}.</>}
        {shown !== 'en' && <> A translation, for a native speaker to check before use.</>}
      </p>
    </div>
  );
}

/** The English example's id, which its translations extend with their language, as in sms-otp-verification-hi. */
function baseId(example: TemplateExample): string {
  return example.language === 'en' ? example.id : example.id.slice(0, -(example.language.length + 1));
}
