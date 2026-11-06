import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createBroker, MODEL } from '../broker.mjs';
const request = { method: 'POST', authorization: 'Bearer identity', body: '{}' };
function setup(overrides = {}) {
  let calls = [];
  const handle = createBroker({ owner: 'owner', enabled: true, verify: async () => ({ uid: 'owner' }),
    mint: async body => { calls.push(body); return { value: 'ek_fixture', expires_at: 123 }; }, ...overrides });
  return { handle, calls };
}
test('denied callers cannot mint credentials', async () => {
  const { handle, calls } = setup({ verify: async () => ({ uid: 'stranger' }) });
  assert.equal((await handle(request)).status, 403); assert.equal(calls.length, 0);
});
test('disabled, malformed, and unauthorized requests fail closed', async () => {
  assert.equal((await setup({ enabled: false }).handle(request)).status, 503);
  for (const [change, status] of [[{ body: '{"model":"other"}' },400],[{ authorization: '' },401],[{ method:'GET' },405]])
    assert.equal((await setup().handle({ ...request, ...change })).status, status);
});
test('provider credentials are short lived and fixed to mini', async () => {
  const { handle, calls } = setup(); const result = await handle(request);
  assert.deepEqual(result.body, { value:'ek_fixture', expires_at:123 });
  assert.equal(calls[0].session.model, MODEL); assert.equal(calls[0].expires_after.seconds,30);
  assert.equal((await handle(request)).status,429);
});
test('daily limit and day rollover', async () => {
  let now = 0; const { handle } = setup({ now: () => now, dailyLimit:1 });
  assert.equal((await handle(request)).status,200); now = 20000;
  assert.equal((await handle(request)).status,429); now = 86400000;
  assert.equal((await handle(request)).status,200);
});
test('upstream errors do not disclose secrets', async () => {
  const { handle } = setup({ mint: async () => { throw Error('sk_secret'); } });
  const result = await handle(request); assert.equal(result.status,502);
  assert.ok(!JSON.stringify(result).includes('sk_secret'));
});
