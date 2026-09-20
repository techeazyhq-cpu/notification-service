// Local stand-in for SMS, WhatsApp and Push gateways (like Mailpit is for email).
// POST /sms | /whatsapp | /push   -> captures the message, replies 200 {"id": "..."}
// GET  /                          -> web UI (auto-refreshing inbox per channel)
// GET  /messages[?channel=sms]    -> JSON;  DELETE /messages -> clear
// POST /admin/fail?status=503&count=3 -> make the next N sends fail with that status (tests retries)
// Recipients ending in 0400 always get HTTP 400 (tests permanent failure).
const http = require('node:http');
const { randomUUID } = require('node:crypto');

const PORT = process.env.PORT || 9000;
const MAX = 2000;
const CHANNELS = ['sms', 'whatsapp', 'push'];
const messages = [];
let failNext = { count: 0, status: 503 };

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

const readBody = (req) => new Promise((resolve, reject) => {
  let data = '';
  req.on('data', (c) => { data += c; if (data.length > 1e6) req.destroy(); });
  req.on('end', () => resolve(data));
  req.on('error', reject);
});

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function page() {
  const rows = messages.slice().reverse().map((m) => `
    <div class="m ${m.channel}"><b>${m.channel.toUpperCase()}</b> to <code>${esc(m.to)}</code>
    <small>${esc(m.receivedAt)}</small>${m.subject ? `<div><i>${esc(m.subject)}</i></div>` : ''}<pre>${esc(m.body)}</pre></div>`).join('');
  return `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="3">
  <title>Notification catcher</title>
  <style>body{font:14px system-ui;max-width:760px;margin:24px auto;padding:0 16px}
  .m{border:1px solid #ccd;border-left-width:6px;border-radius:6px;padding:8px 12px;margin:8px 0}
  .sms{border-left-color:#2a7}.whatsapp{border-left-color:#25d366}.push{border-left-color:#57f}
  pre{white-space:pre-wrap;margin:4px 0 0}small{color:#667;margin-left:8px}</style>
  <h2>Catcher &mdash; ${messages.length} message(s)</h2>
  <p>Captures SMS, WhatsApp and Push sends. <a href="/messages">JSON</a></p>${rows || '<p>Nothing yet.</p>'}`;
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (req.method === 'GET' && url.pathname === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(page());
    }
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { status: 'UP' });
    if (req.method === 'GET' && url.pathname === '/messages') {
      const ch = url.searchParams.get('channel');
      return json(res, 200, ch ? messages.filter((m) => m.channel === ch) : messages);
    }
    if (req.method === 'DELETE' && url.pathname === '/messages') { messages.length = 0; return json(res, 200, { cleared: true }); }
    if (req.method === 'POST' && url.pathname === '/admin/fail') {
      failNext = { count: Number(url.searchParams.get('count') || 1), status: Number(url.searchParams.get('status') || 503) };
      return json(res, 200, failNext);
    }
    const channel = url.pathname.slice(1);
    if (req.method === 'POST' && CHANNELS.includes(channel)) {
      const payload = JSON.parse((await readBody(req)) || '{}');
      if (failNext.count > 0) {
        failNext.count--;
        return json(res, failNext.status, { error: 'injected failure' });
      }
      if (String(payload.to || '').endsWith('0400')) return json(res, 400, { error: 'invalid recipient' });
      const id = randomUUID();
      messages.push({ id, channel, receivedAt: new Date().toISOString(), ...payload });
      if (messages.length > MAX) messages.shift();
      return json(res, 200, { id });
    }
    json(res, 404, { error: 'not found' });
  } catch (e) {
    json(res, 400, { error: String(e.message || e) });
  }
}).listen(PORT, () => console.log(`catcher listening on ${PORT}`));
