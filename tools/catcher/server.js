// Local stand-in for SMS, WhatsApp and Push gateways (like Mailpit is for email). Not for production use.
// POST /sms | /whatsapp | /push   -> captures the message, replies 200 {"id": "..."}
// GET  /                          -> web UI (auto-refreshing inbox per channel)
// GET  /messages[?channel=sms]    -> JSON;  DELETE /messages -> clear
// POST /admin/fail?status=503&count=3 -> make the next N sends fail with that status (tests retries)
// Recipients ending in 0400 always get HTTP 400 (tests permanent failure).
const http = require('node:http');
const { randomUUID } = require('node:crypto');

const PORT = process.env.PORT || 9000;
const MAX_MESSAGES = 2000;
const MAX_BODY_BYTES = 1e6;
const CHANNELS = new Set(['sms', 'whatsapp', 'push']);
const messages = [];
let failNext = { count: 0, status: 503 };

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

const readBody = (req) => new Promise((resolve, reject) => {
  let data = '';
  req.on('data', (chunk) => {
    data += chunk;
    if (data.length > MAX_BODY_BYTES) req.destroy();
  });
  req.on('end', () => resolve(data));
  req.on('error', reject);
});

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ESCAPES[c]);

function renderMessage(m) {
  const subject = m.subject ? `<div><i>${esc(m.subject)}</i></div>` : '';
  return `<div class="m ${m.channel}"><b>${m.channel.toUpperCase()}</b> to <code>${esc(m.to)}</code>
    <small>${esc(m.receivedAt)}</small>${subject}<pre>${esc(m.body)}</pre></div>`;
}

function page() {
  const rows = messages.slice().reverse().map(renderMessage).join('');
  return `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="3">
  <title>Notification catcher</title>
  <style>body{font:14px system-ui;max-width:760px;margin:24px auto;padding:0 16px}
  .m{border:1px solid #ccd;border-left-width:6px;border-radius:6px;padding:8px 12px;margin:8px 0}
  .sms{border-left-color:#2a7}.whatsapp{border-left-color:#25d366}.push{border-left-color:#57f}
  pre{white-space:pre-wrap;margin:4px 0 0}small{color:#667;margin-left:8px}</style>
  <h2>Catcher &mdash; ${messages.length} message(s)</h2>
  <p>Captures SMS, WhatsApp and Push sends. <a href="/messages">JSON</a></p>${rows || '<p>Nothing yet.</p>'}`;
}

async function capture(req, res, channel) {
  const payload = JSON.parse((await readBody(req)) || '{}');
  if (failNext.count > 0) {
    failNext.count--;
    return json(res, failNext.status, { error: 'injected failure' });
  }
  if (String(payload.to || '').endsWith('0400')) return json(res, 400, { error: 'invalid recipient' });
  const id = randomUUID();
  messages.push({ id, receivedAt: new Date().toISOString(), ...payload, channel }); // path channel wins over payload's
  if (messages.length > MAX_MESSAGES) messages.shift();
  return json(res, 200, { id });
}

// Route table: "METHOD /path" -> handler(req, res, params)
const routes = {
  'GET /': (req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(page());
  },
  'GET /health': (req, res) => json(res, 200, { status: 'UP' }),
  'GET /messages': (req, res, params) => {
    const channel = params.get('channel');
    json(res, 200, channel ? messages.filter((m) => m.channel === channel) : messages);
  },
  'DELETE /messages': (req, res) => {
    messages.length = 0;
    json(res, 200, { cleared: true });
  },
  'POST /admin/fail': (req, res, params) => {
    failNext = { count: Number(params.get('count') || 1), status: Number(params.get('status') || 503) };
    json(res, 200, failNext);
  },
};

async function handle(req, res) {
  const [pathname, query = ''] = req.url.split('?');
  const params = new URLSearchParams(query);
  const route = routes[`${req.method} ${pathname}`];
  if (route) return route(req, res, params);
  const channel = pathname.slice(1);
  if (req.method === 'POST' && CHANNELS.has(channel)) return capture(req, res, channel);
  return json(res, 404, { error: 'not found' });
}

http.createServer((req, res) => {
  handle(req, res).catch((e) => json(res, 400, { error: String(e.message || e) }));
}).listen(PORT, () => console.log(`catcher listening on ${PORT}`));
