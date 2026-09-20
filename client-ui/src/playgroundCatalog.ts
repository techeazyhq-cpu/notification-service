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

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

export interface QueryParam { name: string; example: string; description: string }

export interface PlaygroundEndpoint {
  id: string;
  group: string;
  title: string;
  method: HttpMethod;
  path: string;
  description: string;
  pathParams?: Record<string, string>;
  query?: QueryParam[];
  body?: unknown;
  idempotency?: boolean;
  upload?: boolean;
  live?: boolean;
}

export const CATALOG: PlaygroundEndpoint[] = [
  {
    id: 'me', group: 'Account', title: 'Who am I', method: 'GET', path: '/v1/me',
    description: 'Your client name and the channels you may use. A good first call to check your key.',
  },
  {
    id: 'send', group: 'Send', title: 'Send one message', method: 'POST', path: '/v1/notifications', live: true, idempotency: true,
    description: 'Sends a real message. Use templateName, or subject and body inline. Add "from" to send e-mail from one of your confirmed sender addresses.',
    body: { channel: 'SMS', recipient: '+14155550123', body: 'Your code is {{code}}', variables: { code: '481516' }, clientReference: 'playground-test' },
  },
  {
    id: 'bulk', group: 'Send', title: 'Send to many (JSON)', method: 'POST', path: '/v1/notifications/bulk', live: true, idempotency: true,
    description: 'Each recipient can carry its own variables. Sends real messages.',
    body: {
      channel: 'SMS', body: 'Hi {{name}}, your order {{orderId}} shipped',
      recipients: [
        { recipient: '+14155550123', variables: { name: 'Ada', orderId: '1001' } },
        { recipient: '+14155550124', variables: { name: 'Grace', orderId: '1002' } },
      ],
      clientReference: 'playground-bulk',
    },
  },
  {
    id: 'upload', group: 'Send', title: 'Send to many (CSV upload)', method: 'POST', path: '/v1/notifications/bulk/upload', live: true, idempotency: true, upload: true,
    description: 'CSV with a header row. The recipient column is the address; other columns become variables. Sends real messages.',
    body: { channel: 'SMS', body: 'Hi {{name}}', csv: 'recipient,name\n+14155550123,Ada\n+14155550124,Grace' },
  },
  {
    id: 'list', group: 'Track', title: 'List requests', method: 'GET', path: '/v1/notifications',
    description: 'Newest first.',
    query: [
      { name: 'channel', example: '', description: 'EMAIL, SMS, WHATSAPP or PUSH' },
      { name: 'clientReference', example: '', description: 'contains, case-insensitive' },
      { name: 'page', example: '0', description: 'zero based' },
      { name: 'size', example: '10', description: 'page size' },
    ],
  },
  {
    id: 'summary', group: 'Track', title: 'Summary', method: 'GET', path: '/v1/notifications/summary',
    description: 'Message counts by channel and status.',
    query: [{ name: 'hours', example: '24', description: 'look-back window' }],
  },
  {
    id: 'status', group: 'Track', title: 'Request status', method: 'GET', path: '/v1/notifications/{requestId}',
    description: 'Counts per status for one request. Paste the requestId from a send response.',
    pathParams: { requestId: '' },
  },
  {
    id: 'messages', group: 'Track', title: 'Messages of a request', method: 'GET', path: '/v1/notifications/{requestId}/messages',
    description: 'One row per recipient.', pathParams: { requestId: '' },
    query: [
      { name: 'status', example: '', description: 'PENDING, QUEUED, PROCESSING, RETRYING, SENT or FAILED' },
      { name: 'recipient', example: '', description: 'contains' },
      { name: 'page', example: '0', description: 'zero based' },
      { name: 'size', example: '20', description: 'page size' },
    ],
  },
  {
    id: 'templates', group: 'Templates', title: 'List templates', method: 'GET', path: '/v1/templates',
    description: 'Your own templates plus shared read-only ones.',
  },
  {
    id: 'template-preview', group: 'Templates', title: 'Preview content', method: 'POST', path: '/v1/templates/preview',
    description: 'Renders with sample values. Nothing is stored or sent.',
    body: { subject: 'Order {{orderId}}', body: 'Hi {{name}}, thanks for your order.', variables: { name: 'Ada' } },
  },
  {
    id: 'template-create', group: 'Templates', title: 'Create a template', method: 'POST', path: '/v1/templates',
    description: 'Creates a template in your account (it does not send anything).',
    body: { name: 'playground-otp', channel: 'SMS', body: 'Your code is {{code}}' },
  },
  {
    id: 'template-delete', group: 'Templates', title: 'Delete a template', method: 'DELETE', path: '/v1/templates/{id}',
    description: 'Deletes one of your own templates. Requests already accepted are not affected.', pathParams: { id: '' },
  },
  {
    id: 'senders', group: 'Sender addresses', title: 'List sender addresses', method: 'GET', path: '/v1/senders',
    description: 'E-mail addresses you may send from.',
  },
  {
    id: 'sender-add', group: 'Sender addresses', title: 'Register a sender address', method: 'POST', path: '/v1/senders',
    description: 'Sends a confirmation e-mail to the address.', body: { email: 'orders@example.com', displayName: 'Example Orders' },
  },
  {
    id: 'billing-account', group: 'Billing', title: 'Billing account', method: 'GET', path: '/v1/billing/account',
    description: 'Plan, mode, credit balance or spend cap.',
  },
  {
    id: 'billing-usage', group: 'Billing', title: 'Usage for a month', method: 'GET', path: '/v1/billing/usage',
    description: 'Billable usage and the estimated charge.',
    query: [{ name: 'month', example: '', description: 'YYYY-MM, default is this month' }],
  },
  {
    id: 'invoices', group: 'Billing', title: 'Invoices', method: 'GET', path: '/v1/billing/invoices',
    description: 'Issued, paid and void invoices.',
  },
  {
    id: 'ledger', group: 'Billing', title: 'Prepaid credit ledger', method: 'GET', path: '/v1/billing/ledger',
    description: 'Credit movements for prepaid accounts.',
    query: [{ name: 'page', example: '0', description: 'zero based' }, { name: 'size', example: '20', description: 'page size' }],
  },
];
