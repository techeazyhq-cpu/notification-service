const KEY = 'notification-client-api-key';

export const CHANNELS = ['EMAIL', 'SMS', 'WHATSAPP', 'PUSH'] as const;
export type Channel = (typeof CHANNELS)[number];
export const MESSAGE_STATUSES = ['PENDING', 'QUEUED', 'PROCESSING', 'RETRYING', 'SENT', 'FAILED'] as const;
export type MessageStatus = (typeof MESSAGE_STATUSES)[number];
export type RequestStatus = 'PROCESSING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED';

/** The API key lives in sessionStorage only: it disappears when the tab closes. */
export const session = {
  get: () => sessionStorage.getItem(KEY),
  set: (apiKey: string) => sessionStorage.setItem(KEY, apiKey),
  clear: () => sessionStorage.removeItem(KEY),
};

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

async function request(path: string, init: { method?: string; body?: unknown } = {}): Promise<Response> {
  const headers: Record<string, string> = { 'X-API-Key': session.get() ?? '' };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method: init.method ?? 'GET',
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (res.status === 401) {
    session.clear();
    window.dispatchEvent(new Event('auth-lost'));
    throw new ApiError(401, 'Your API key is not valid or has been disabled');
  }
  if (res.status === 429) {
    throw new ApiError(429, `Rate limited, retry in ${res.headers.get('Retry-After') ?? 'a few'} seconds`);
  }
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      message = (await res.json()).message ?? message;
    } catch { /* keep the status text */ }
    throw new ApiError(res.status, message);
  }
  return res;
}

export async function api<T>(path: string): Promise<T> {
  return (await request(path)).json() as Promise<T>;
}

/** POST / PUT / DELETE with an optional JSON body; resolves to the parsed response, or undefined for 204. */
export async function send<T = void>(method: 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T> {
  const res = await request(path, { method, body });
  return (res.status === 204 ? undefined : await res.json()) as T;
}

/** Fetches with the API key header (a plain link cannot) and hands the result to the browser as a download. */
export async function download(path: string, filename: string): Promise<void> {
  const blob = await (await request(path)).blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export interface Me { id: string; name: string; allowedChannels: Channel[] }
export interface StatusCounts { pending: number; queued: number; processing: number; retrying: number; sent: number; failed: number }
export interface RequestView {
  requestId: string; kind: 'SINGLE' | 'BULK'; channel: Channel; status: RequestStatus; total: number;
  counts: StatusCounts; clientReference?: string; createdAt: string;
}
export interface MessageView {
  messageId: string; recipient: string; status: MessageStatus; attempts: number; lastError?: string;
  providerMessageId?: string; sentAt?: string; updatedAt: string;
}
export interface PageView<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }
export interface ChannelStatusCount { channel: Channel; status: MessageStatus; count: number }
export interface Summary { hours: number; requests: number; counts: ChannelStatusCount[] }

export const inFlight = (c: StatusCounts) => c.pending + c.queued + c.processing + c.retrying;

export interface Template {
  id: string; name: string; channel: Channel; subject?: string; body: string; variables: string[];
  scope: 'OWNED' | 'SHARED'; readOnly: boolean; createdAt: string; updatedAt: string;
}
export interface TemplateInput { name: string; channel: Channel; subject?: string; body: string }
export interface Preview { subject?: string; body: string; requiredVariables: string[]; missingVariables: string[] }

/** Same placeholder syntax the server uses: {{name}}; {{recipient}} is built in and never needs a value. */
const PLACEHOLDER = /\{\{\s*([\w.-]+)\s*\}\}/g;
export function variablesOf(...texts: (string | undefined)[]): string[] {
  const names = new Set<string>();
  for (const text of texts) for (const m of (text ?? '').matchAll(PLACEHOLDER)) names.add(m[1]);
  names.delete('recipient');
  return [...names];
}

export interface BillingRate { channel: Channel; unitPrice: string; freeAllowance: number }
export interface BillingAccount {
  planName: string; currency: string; mode: 'POSTPAID' | 'PREPAID'; status: 'ACTIVE' | 'SUSPENDED';
  creditBalance?: string; monthlySpendCap?: string; platformFee: string; taxRate: string; rates: BillingRate[];
}
export interface BillingLine { kind: 'USAGE' | 'PLATFORM_FEE'; channel?: Channel; description: string; quantity: number; unitPrice: string; amount: string }
export interface BillingUsage { month: string; currency: string; estimate: boolean; lines: BillingLine[]; subtotal: string; tax: string; total: string }
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'VOID';
export interface InvoiceSummary {
  id: string; number?: string; month: string; status: InvoiceStatus; currency: string; total: string; outstanding: string;
  issuedAt?: string; dueAt?: string;
}
export interface InvoicePayment { amount: string; method: string; reference: string; receivedAt: string }
export interface InvoiceDetail extends InvoiceSummary {
  lines: BillingLine[]; subtotal: string; taxRate: string; tax: string; paid: string; paidAt?: string; payments: InvoicePayment[];
}
export interface LedgerEntry { id: string; type: 'TOP_UP' | 'HOLD' | 'SETTLEMENT' | 'ADJUSTMENT'; amount: string; reference: string; description?: string; createdAt: string }
export interface LedgerPage { currency: string; items: LedgerEntry[]; total: number; page: number; size: number }

export const money = (amount: string, currency: string) => `${amount} ${currency}`;
