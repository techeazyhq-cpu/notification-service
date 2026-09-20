const KEY = 'notification-admin-auth';

export const CHANNELS = ['EMAIL', 'SMS', 'WHATSAPP', 'PUSH'] as const;
export type Channel = (typeof CHANNELS)[number];

export const auth = {
  get: () => sessionStorage.getItem(KEY),
  set: (user: string, password: string) => sessionStorage.setItem(KEY, 'Basic ' + btoa(`${user}:${password}`)),
  clear: () => sessionStorage.removeItem(KEY),
};

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch('/api/admin' + path, {
    method,
    headers: { Authorization: auth.get() ?? '', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) {
    auth.clear();
    window.dispatchEvent(new Event('auth-lost'));
    throw new ApiError(401, 'Not authorised');
  }
  const text = await res.text();
  if (!res.ok) {
    let message = text;
    try {
      const j = JSON.parse(text);
      message = j.message || j.error || text;
      if (j.errors?.length) message = j.errors.map((e: { defaultMessage: string; field: string }) => `${e.field} ${e.defaultMessage}`).join('; ');
    } catch { /* keep raw text */ }
    throw new ApiError(res.status, message || `HTTP ${res.status}`);
  }
  return (text ? JSON.parse(text) : null) as T;
}

export interface Client { id: string; name: string; status: 'ACTIVE' | 'DISABLED'; allowedChannels: Channel[]; apiKeyPrefix: string; createdAt: string }
/** ownerClientId is null for shared templates (managed here); otherwise the template belongs to that client. */
export interface Template { id: string; ownerClientId?: string; ownerName?: string; name: string; channel: Channel; subject?: string; body: string }
export interface Provider { id: string; channel: Channel; name: string; type: 'SMTP' | 'HTTP_JSON'; settings: Record<string, string>; enabled: boolean; priority: number }
export interface RateLimit { id: string; scope: 'CLIENT_API' | 'CLIENT_CHANNEL' | 'GLOBAL_CHANNEL'; clientId?: string; channel?: Channel; ratePerSecond: number; burst: number; enabled: boolean }
export interface Count { channel: string; status: string; count: number }
export interface Summary { hours: number; counts: Count[]; backlog: number; requests: number }
export interface Point { bucket: string; channel: string; status: string; count: number }
export interface MessageRow { id: string; requestId: string; clientId: string; clientName: string; channel: string; recipient: string; status: string; attempts: number; lastError?: string; providerMessageId?: string; createdAt: string; sentAt?: string }
export interface MessagePage { items: MessageRow[]; page: number; size: number; totalItems: number }

export async function download(path: string, filename: string): Promise<void> {
  const res = await fetch('/api/admin' + path, { headers: { Authorization: auth.get() ?? '' } });
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`);
  const url = URL.createObjectURL(await res.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export interface BillingRate { channel: Channel; unitPrice: string; freeAllowance: number }
export interface BillingPlan { id: string; name: string; currency: string; platformFee: string; taxRate: number; active: boolean; rates: BillingRate[] }
export interface BillingAccount {
  clientId: string; clientName: string; planId: string; planName: string; currency: string; mode: 'POSTPAID' | 'PREPAID';
  status: 'ACTIVE' | 'SUSPENDED'; creditBalance?: string; monthlySpendCap?: string; billingEmail?: string;
}
export interface BillingLine { kind: string; channel?: Channel; description: string; quantity: number; unitPrice: string; amount: string }
export interface BillingPayment { amount: string; method: string; reference: string; receivedAt: string }
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'VOID';
export interface BillingInvoice {
  id: string; number?: string; clientId: string; clientName: string; month: string; status: InvoiceStatus; currency: string;
  lines: BillingLine[]; subtotal: string; taxRate: number; tax: string; total: string; paid: string; outstanding: string;
  issuedAt?: string; dueAt?: string; paidAt?: string; voidReason?: string; payments: BillingPayment[];
}
export interface LedgerEntry { id: string; type: string; amount: string; reference: string; description?: string; createdAt: string }
export interface LedgerPage { items: LedgerEntry[]; total: number; page: number; size: number }
export interface GenerationReport { created: number; existing: number; failures: string[] }
export const money = (amount: string, currency: string) => `${amount} ${currency}`;
