export const MODEL = 'gpt-realtime-2.1-mini';
export function createBroker({ verify, mint, owner, enabled = false, now = Date.now, dailyLimit = 100 }) {
  let day = -1, used = 0, last = -Infinity, pending = false;
  return async function handle({ method, authorization, body }) {
    const result = (status, value) => ({ status, body: value });
    if (!enabled) return result(503, { error: 'Voice disabled' });
    if (method !== 'POST') return result(405, { error: 'POST required' });
    if (body.length > 1024 || body.trim() !== '{}') return result(400, { error: 'Invalid request' });
    if (!owner || !authorization?.startsWith('Bearer ')) return result(401, { error: 'Authorization required' });
    let identity;
    try { identity = await verify(authorization.slice(7)); } catch { return result(401, { error: 'Invalid authorization' }); }
    if (identity.uid !== owner) return result(403, { error: 'Voice access unavailable' });
    const today = Math.floor(now() / 86400000);
    if (day !== today) { day = today; used = 0; }
    if (pending || used >= dailyLimit || now() - last < 10_000) return result(429, { error: 'Voice request limit reached' });
    pending = true; used++; last = now();
    try {
      const token = await mint({ expires_after: { anchor: 'created_at', seconds: 30 }, session: {
        type: 'realtime', model: MODEL, max_output_tokens: 1200, output_modalities: ['audio'],
        audio: { input: { format: { type: 'audio/pcm', rate: 24000 },
          turn_detection: { type: 'server_vad', create_response: false, interrupt_response: false, silence_duration_ms: 900 } } }
      } });
      if (typeof token.value !== 'string' || !token.value.startsWith('ek_')) throw Error('Invalid upstream response');
      return result(200, { value: token.value, expires_at: token.expires_at });
    } catch { return result(502, { error: 'Voice provider unavailable' }); }
    finally { pending = false; }
  };
}
