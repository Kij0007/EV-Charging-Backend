import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../../main/resources/static/assets/api.js', import.meta.url), 'utf8');
let moduleNumber = 0;
const fresh = () => import(`data:text/javascript;base64,${Buffer.from(source + `\n// instance ${moduleNumber++}`).toString('base64')}`);
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
const originalFetch = globalThis.fetch;
globalThis.window = new EventTarget();

test('GET sends same-origin credentials without requesting a CSRF token', async () => {
  const api = await fresh();
  const calls = [];
  globalThis.fetch = async (path, options) => { calls.push({ path, options }); return json([{ id: 7 }]); };
  assert.deepEqual(await api.request('/api/stations'), [{ id: 7 }]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.headers.Accept, 'application/json');
  assert.equal(calls[0].options.body, undefined);
});

test('mutations obtain and reuse CSRF token while preserving idempotency header and JSON body', async () => {
  const api = await fresh();
  const calls = [];
  globalThis.fetch = async (path, options) => { calls.push({ path, options }); return path.endsWith('/csrf') ? json({ token: 'token-A', headerName: 'X-CSRF-TOKEN' }) : json({ bookingId: 1 }); };
  await api.request('/api/bookings', { method: 'POST', body: { stationId: 8 }, headers: { 'Idempotency-Key': 'attempt-1' } });
  await api.request('/api/profile', { method: 'PUT', body: { name: 'A' } });
  assert.equal(calls.filter(call => call.path.endsWith('/csrf')).length, 1);
  assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'token-A');
  assert.equal(calls[1].options.headers['Idempotency-Key'], 'attempt-1');
  assert.equal(calls[1].options.body, '{"stationId":8}');
});

test('explicit CSRF reset fetches a fresh token after authentication changes', async () => {
  const api = await fresh(); let tokens = 0; const sent = [];
  globalThis.fetch = async (path, options) => path.endsWith('/csrf') ? json({ token: `token-${++tokens}`, headerName: 'X-CSRF-TOKEN' }) : (sent.push(options.headers['X-CSRF-TOKEN']), new Response(null, { status: 204 }));
  await api.request('/api/auth/logout', { method: 'POST' });
  api.resetCsrf();
  await api.request('/api/notifications/1/read', { method: 'PUT' });
  assert.deepEqual(sent, ['token-1', 'token-2']);
});

test('401 session event is emitted for protected calls, but not silent login checks', async () => {
  const api = await fresh(); let events = 0;
  const handler = () => events++;
  window.addEventListener('voltway:session-expired', handler);
  globalThis.fetch = async () => json({ message: 'Sign in required' }, 401);
  await assert.rejects(api.request('/api/auth/me', { silent: true }), error => error.status === 401);
  assert.equal(events, 0);
  await assert.rejects(api.request('/api/bookings'), error => error.message === 'Sign in required');
  assert.equal(events, 1);
  window.removeEventListener('voltway:session-expired', handler);
});

test('403 invalidates cached CSRF for a subsequent user retry without automatically repeating mutations', async () => {
  const api = await fresh(); let tokens = 0; let mutations = 0;
  globalThis.fetch = async path => path.endsWith('/csrf') ? json({ token: `token-${++tokens}`, headerName: 'X-CSRF-TOKEN' }) : ++mutations === 1 ? json({ message: 'Forbidden' }, 403) : json({ id: 2 });
  await assert.rejects(api.request('/api/stations', { method: 'POST', body: {} }), error => error.status === 403);
  assert.equal(mutations, 1);
  await api.request('/api/stations', { method: 'POST', body: {} });
  assert.equal(tokens, 2);
  assert.equal(mutations, 2);
});

test('204 response has no JSON body and returns null', async () => {
  const api = await fresh();
  globalThis.fetch = async () => new Response(null, { status: 204 });
  assert.equal(await api.request('/api/example'), null);
});

test('network failure returns useful message without exposing raw transport error', async () => {
  const api = await fresh();
  globalThis.fetch = async () => { throw new TypeError('internal connection implementation details'); };
  await assert.rejects(api.request('/api/stations'), error => error.status === 0 && error.message.includes('could not reach the server') && !error.message.includes('implementation'));
});

test('non-JSON error response is converted into a safe user-facing error', async () => {
  const api = await fresh();
  globalThis.fetch = async () => new Response('<html>server trace</html>', { status: 500 });
  await assert.rejects(api.request('/api/stations'), error => error.status === 500 && error.message === 'The request could not be completed.');
});

test('optional visitor information can use a short timeout without changing normal request defaults', async () => {
  const api = await fresh();
  globalThis.fetch = async (_path, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  await assert.rejects(api.request('/api/site-info', { timeoutMs: 1, silent: true }), error => error.status === 0 && error.message.includes('took too long'));
});

test.after(() => { globalThis.fetch = originalFetch; });
