import http from 'node:http';
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { createBroker } from './broker.mjs';
initializeApp();
if (!process.env.VOICE_OWNER_UID || !process.env.OPENAI_API_KEY) throw Error('Voice owner and provider configuration required');
const handle = createBroker({
  owner: process.env.VOICE_OWNER_UID, enabled: process.env.VOICE_ENABLED === 'true',
  verify: token => getAuth().verifyIdToken(token, true),
  mint: async body => {
    const response = await fetch('https://api.openai.com/v1/realtime/client_secrets', {
      method: 'POST', headers: { Authorization: `Bearer ${process.env.OPENAI_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: AbortSignal.timeout(8000), redirect: 'error'
    });
    if (!response.ok) throw Error('Provider unavailable');
    const text = await response.text();
    if (text.length > 16000) throw Error('Provider response too large');
    return JSON.parse(text);
  }
});
const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json');
  if (req.url !== '/session') { res.writeHead(404); res.end('{}'); return; }
  try {
    let body = '';
    for await (const part of req) { body += part; if (body.length > 1024) { res.writeHead(413); res.end('{}'); return; } }
    const result = await handle({ method: req.method, authorization: req.headers.authorization, body });
    res.writeHead(result.status); res.end(JSON.stringify(result.body));
  } catch { if (!res.headersSent) res.writeHead(400); res.end('{}'); }
});
server.requestTimeout = 10000;
server.headersTimeout = 10000;
server.listen(Number(process.env.PORT || 8080), '0.0.0.0');
