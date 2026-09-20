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
import QRCode from 'qrcode';
import { Account as AccountInfo, api, TwoFactorSetup } from '../api';
import { useLoad } from '../hooks';

function Notice({ message, isError }: Readonly<{ message: string; isError: boolean }>) {
  return message ? <p className={isError ? 'error' : 'muted'}>{message}</p> : null;
}

function PasswordForm({ onChanged }: Readonly<{ onChanged: () => void }>) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [message, setMessage] = useState('');
  const [isError, setIsError] = useState(false);

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    if (next !== confirm) {
      setMessage('The new password and its confirmation are different');
      setIsError(true);
      return;
    }
    try {
      await api('POST', '/auth/password', { currentPassword: current, newPassword: next });
      setMessage('Password changed. Your other sessions have been signed out.');
      setIsError(false);
      setCurrent('');
      setNext('');
      setConfirm('');
      onChanged();
    } catch (err) {
      setMessage((err as Error).message);
      setIsError(true);
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h3>Change password</h3>
      <div className="row">
        <label><span>Current password</span><input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required /></label>
        <label><span>New password (at least 12 characters)</span><input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={12} required /></label>
        <label><span>Repeat new password</span><input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required /></label>
        <button type="submit" className="primary">Change password</button>
      </div>
      <Notice message={message} isError={isError} />
    </form>
  );
}

function RecoveryCodes({ codes, onDone }: Readonly<{ codes: string[]; onDone: () => void }>) {
  const text = codes.join('\n');
  const save = () => {
    const url = URL.createObjectURL(new Blob([text + '\n'], { type: 'text/plain' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'notification-admin-recovery-codes.txt';
    link.click();
    URL.revokeObjectURL(url);
  };
  return (
    <div className="card">
      <h3>Save your recovery codes</h3>
      <p className="muted">Each code signs you in once if you lose your authenticator. They are shown only now; store them somewhere safe.</p>
      <pre className="mono">{text}</pre>
      <div className="row">
        <button type="button" onClick={() => navigator.clipboard?.writeText(text)}>Copy</button>
        <button type="button" onClick={save}>Download</button>
        <button type="button" className="primary" onClick={onDone}>I have saved them</button>
      </div>
    </div>
  );
}

function EnableTwoFactor({ onEnabled }: Readonly<{ onEnabled: (codes: string[]) => void }>) {
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null);
  const [qr, setQr] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (setup) QRCode.toDataURL(setup.otpauthUri, { margin: 1, width: 200 }).then(setQr, () => setQr(''));
  }, [setup]);

  async function begin() {
    try {
      setSetup(await api<TwoFactorSetup>('POST', '/auth/2fa/setup'));
      setMessage('');
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function confirm(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await api<{ recoveryCodes: string[] }>('POST', '/auth/2fa/enable', { code });
      onEnabled(r.recoveryCodes);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  if (!setup) {
    return (
      <>
        <p className="muted">Two-factor authentication is off. When it is on, signing in needs a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, Authy...).</p>
        <button type="button" className="primary" onClick={begin}>Set up two-factor authentication</button>
        <Notice message={message} isError />
      </>
    );
  }
  return (
    <form onSubmit={confirm}>
      <ol>
        <li>Scan this QR code with your authenticator app.</li>
        <li>Or enter the key by hand: <span className="mono">{setup.secret}</span></li>
        <li>Type the 6-digit code the app shows to finish.</li>
      </ol>
      {qr && <img src={qr} alt="QR code for the authenticator app" width={200} height={200} />}
      <div className="row">
        <label><span>Code</span><input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} required /></label>
        <button type="submit" className="primary">Turn on</button>
        <button type="button" onClick={() => setSetup(null)}>Cancel</button>
      </div>
      <Notice message={message} isError />
    </form>
  );
}

function ProtectedAction({ title, button, danger, run }: Readonly<{ title: string; button: string; danger?: boolean; run: (password: string, code: string) => Promise<void> }>) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [message, setMessage] = useState('');

  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      await run(password, code);
      setPassword('');
      setCode('');
      setMessage('');
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  return (
    <form onSubmit={submit} style={{ marginTop: 12 }}>
      <h4>{title}</h4>
      <div className="row">
        <label><span>Password</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        <label><span>Code or recovery code</span><input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" required /></label>
        <button type="submit" className={danger ? 'danger' : ''}>{button}</button>
      </div>
      <Notice message={message} isError />
    </form>
  );
}

export default function Account() {
  const { data, error, reload } = useLoad(() => api<AccountInfo>('GET', '/auth/me'));
  const [codes, setCodes] = useState<string[] | null>(null);

  const disable = async (password: string, verificationCode: string) => {
    await api('POST', '/auth/2fa/disable', { password, verificationCode });
    reload();
  };
  const regenerate = async (password: string, verificationCode: string) => {
    const r = await api<{ recoveryCodes: string[] }>('POST', '/auth/2fa/recovery-codes', { password, verificationCode });
    setCodes(r.recoveryCodes);
    reload();
  };

  return (
    <>
      <h1>My account</h1>
      {error && <p className="error">{error}</p>}
      {data && <p className="muted">Signed in as <b>{data.username}</b></p>}
      {data?.initialPassword && <div className="card error">You are still using the initial password. Change it now.</div>}
      <PasswordForm onChanged={reload} />
      {codes && <RecoveryCodes codes={codes} onDone={() => setCodes(null)} />}
      <div className="card">
        <h3>Two-factor authentication <span className={`badge ${data?.twoFactorEnabled ? 'ACTIVE' : 'DISABLED'}`}>{data?.twoFactorEnabled ? 'ON' : 'OFF'}</span></h3>
        {data && !data.twoFactorEnabled && <EnableTwoFactor onEnabled={(c) => { setCodes(c); reload(); }} />}
        {data?.twoFactorEnabled && (
          <>
            <p className="muted">{data.recoveryCodesRemaining} recovery codes left.</p>
            <ProtectedAction title="Get new recovery codes (the old ones stop working)" button="Generate new codes" run={regenerate} />
            <ProtectedAction title="Turn two-factor authentication off" button="Turn off" danger run={disable} />
          </>
        )}
      </div>
    </>
  );
}
