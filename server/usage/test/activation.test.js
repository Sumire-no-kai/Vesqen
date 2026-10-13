import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {handle} from '../src/worker.js';
import {environment} from './database.js';

const now = Date.parse('2026-11-01T12:00:00Z');
const secret = 'whsec_test_secret';
const code = n => `CODE${String(n).padStart(3, '0')}`.padEnd(117, '0');
const session = (id, extra = {}) => ({id, object:'checkout.session', mode:'payment', payment_status:'paid',
  payment_intent:`pi_${id.slice(8)}`, customer_details:{email:'buyer@example.com', name:'Private Buyer'}, ...extra});

function setup({codes = 3, ingest = 'open', stripe = {}} = {}) {
  const env = {...environment(), ACTIVATION_INGEST:ingest, ACTIVATION_ORIGIN:'https://vesqen.sumirenokai.com',
    STRIPE_WEBHOOK_SECRET:secret, STRIPE_SECRET_KEY:'rk_test_read_only'};
  if (ingest === null) delete env.ACTIVATION_INGEST;
  env.ACTIVATION_LIMITER = {limit:async argument => {env.calls.push(argument); return {success:true};}};
  for (let n = 1; n <= codes; n++) env.DB.prepare('INSERT INTO activation_codes(key_id, serial, code) VALUES(?,?,?)').bind(1, n, code(n)).run();
  const requests = [];
  const fetcher = async (url, init) => {
    requests.push({url, authorization:init.headers.Authorization});
    const id = url.split('/').pop();
    return id in stripe ? new Response(JSON.stringify(stripe[id]), {status:200}) : new Response('{}', {status:404});
  };
  return {env, requests, fetcher};
}

function event(type, object, {at = now, key = secret} = {}) {
  const payload = JSON.stringify({id:`evt_${Math.random()}`, type, data:{object}});
  const t = Math.floor(at / 1000);
  const signature = createHmac('sha256', key).update(`${t}.${payload}`).digest('hex');
  return new Request('https://example.invalid/v1/stripe/webhook', {method:'POST', body:payload,
    headers:{'content-type':'application/json; charset=utf-8', 'stripe-signature':`t=${t},v1=${signature}`}});
}

const lookup = id => new Request(`https://example.invalid/v1/activation?session=${id}`);
const orders = env => env.DB.rows('SELECT serial, order_id, payment_intent, revoke_reason FROM activation_codes ORDER BY serial');

test('a paid order gets the next code once, however often Stripe delivers the event', async t => {
  const {env} = setup(); t.after(() => env.DB.close());
  const first = session('cs_test_aaaaaaaaaaaa');
  for (let i = 0; i < 3; i++) assert.equal((await handle(event('checkout.session.completed', first), env, now)).status, 200);
  assert.equal((await handle(event('checkout.session.completed', session('cs_test_bbbbbbbbbbbb')), env, now)).status, 200);
  assert.deepEqual(orders(env), [
    {serial:1, order_id:'cs_test_aaaaaaaaaaaa', payment_intent:'pi_aaaaaaaaaaaa', revoke_reason:null},
    {serial:2, order_id:'cs_test_bbbbbbbbbbbb', payment_intent:'pi_bbbbbbbbbbbb', revoke_reason:null},
    {serial:3, order_id:null, payment_intent:null, revoke_reason:null},
  ]);
  // Buyer details in the event are never stored.
  assert.ok(!JSON.stringify(env.DB.rows('SELECT * FROM activation_codes')).match(/buyer|Private/i));
});

test('the purchase page reads its code, with CORS for the website only', async t => {
  const {env, requests, fetcher} = setup(); t.after(() => env.DB.close());
  await handle(event('checkout.session.completed', session('cs_test_aaaaaaaaaaaa')), env, now);
  const response = await handle(lookup('cs_test_aaaaaaaaaaaa'), env, now, fetcher);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {code:code(1)});
  assert.equal(response.headers.get('access-control-allow-origin'), 'https://vesqen.sumirenokai.com');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(requests.length, 0, 'a delivered event needs no call to Stripe');
  assert.deepEqual(env.calls, [{key:'activation'}]);
});

test('before the event arrives, Stripe decides whether the order is paid', async t => {
  const {env, requests, fetcher} = setup({stripe:{
    cs_test_paidpaidpaid: session('cs_test_paidpaidpaid'),
    cs_test_pendingpending: session('cs_test_pendingpending', {payment_status:'unpaid'}),
  }});
  t.after(() => env.DB.close());
  const paid = await handle(lookup('cs_test_paidpaidpaid'), env, now, fetcher);
  assert.deepEqual(await paid.json(), {code:code(1)});
  assert.equal(requests[0].authorization, 'Bearer rk_test_read_only');
  assert.equal((await handle(lookup('cs_test_pendingpending'), env, now, fetcher)).status, 409);
  assert.equal((await handle(lookup('cs_test_unknownunknown'), env, now, fetcher)).status, 404);
  // The late event then finds the order already served.
  await handle(event('checkout.session.completed', session('cs_test_paidpaidpaid')), env, now);
  assert.equal(orders(env).filter(row => row.order_id).length, 1);
});

test('a forged, replayed or unsigned event changes nothing', async t => {
  const {env} = setup(); t.after(() => env.DB.close());
  const order = session('cs_test_aaaaaaaaaaaa');
  assert.equal((await handle(event('checkout.session.completed', order, {key:'whsec_wrong'}), env, now)).status, 400);
  assert.equal((await handle(event('checkout.session.completed', order, {at:now - 600000}), env, now)).status, 400);
  const unsigned = new Request('https://example.invalid/v1/stripe/webhook', {method:'POST', body:JSON.stringify({type:'checkout.session.completed', data:{object:order}})});
  assert.equal((await handle(unsigned, env, now)).status, 400);
  assert.equal(orders(env).filter(row => row.order_id).length, 0);
});

test('a full refund or a dispute revokes the code; a partial refund does not', async t => {
  const {env, fetcher} = setup(); t.after(() => env.DB.close());
  for (const id of ['cs_test_aaaaaaaaaaaa', 'cs_test_bbbbbbbbbbbb', 'cs_test_cccccccccccc'])
    await handle(event('checkout.session.completed', session(id)), env, now);
  await handle(event('charge.refunded', {object:'charge', payment_intent:'pi_aaaaaaaaaaaa', refunded:true}), env, now);
  await handle(event('charge.refunded', {object:'charge', payment_intent:'pi_bbbbbbbbbbbb', refunded:false}), env, now);
  await handle(event('charge.dispute.created', {object:'dispute', payment_intent:'pi_cccccccccccc'}), env, now);
  // The first reason stands.
  await handle(event('charge.dispute.created', {object:'dispute', payment_intent:'pi_aaaaaaaaaaaa'}), env, now);
  assert.deepEqual(orders(env).map(row => row.revoke_reason), ['refund', null, 'dispute']);
  assert.equal((await handle(lookup('cs_test_aaaaaaaaaaaa'), env, now, fetcher)).status, 410);
  assert.equal((await handle(lookup('cs_test_bbbbbbbbbbbb'), env, now, fetcher)).status, 200);
});

test('an empty pool makes Stripe retry and the page say so, instead of losing the order', async t => {
  const {env, fetcher} = setup({codes:0, stripe:{cs_test_aaaaaaaaaaaa:session('cs_test_aaaaaaaaaaaa')}});
  t.after(() => env.DB.close());
  assert.equal((await handle(event('checkout.session.completed', session('cs_test_aaaaaaaaaaaa')), env, now)).status, 503);
  const page = await handle(lookup('cs_test_aaaaaaaaaaaa'), env, now, fetcher);
  assert.equal(page.status, 503);
  assert.deepEqual(await page.json(), {error:'NO_CODES_LEFT'});
});

test('sales stay closed until opened, and requests are checked before anything is read', async t => {
  for (const ingest of [null, 'closed', 'typo']) {
    const {env, fetcher} = setup({ingest}); t.after(() => env.DB.close());
    assert.equal((await handle(event('checkout.session.completed', session('cs_test_aaaaaaaaaaaa')), env, now)).status, 503);
    assert.equal((await handle(lookup('cs_test_aaaaaaaaaaaa'), env, now, fetcher)).status, 503);
    assert.equal(orders(env).filter(row => row.order_id).length, 0);
  }
  const {env, fetcher} = setup(); t.after(() => env.DB.close());
  assert.equal((await handle(lookup('not-a-session'), env, now, fetcher)).status, 400);
  assert.equal((await handle(new Request('https://example.invalid/v1/activation?session=cs_test_aaaaaaaaaaaa&x=1'), env, now, fetcher)).status, 400);
  assert.equal((await handle(new Request('https://example.invalid/v1/activation?session=cs_test_aaaaaaaaaaaa', {method:'POST'}), env, now, fetcher)).status, 405);
  assert.equal((await handle(new Request('https://example.invalid/v1/stripe/webhook'), env, now, fetcher)).status, 405);
  assert.deepEqual(env.calls, []);
});

test('the API host verifies the activation link and sends browsers on to the website', async () => {
  const env = {DB:null}; // Neither answer touches storage, and both work while sales are closed.
  const links = await handle(new Request('https://example.invalid/.well-known/assetlinks.json'), env, now);
  assert.equal(links.status, 200);
  assert.equal(links.headers.get('content-type'), 'application/json');
  const [statement] = await links.json();
  assert.deepEqual(statement.relation, ['delegate_permission/common.handle_all_urls']);
  assert.equal(statement.target.package_name, 'io.github.sumirenokai.vesqen');
  // The application signing certificate of docs/release-keys/vesqen-app-signing-v1-cert.pem.
  assert.deepEqual(statement.target.sha256_cert_fingerprints,
    ['74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93']);
  const english = await handle(new Request('https://example.invalid/activate', {headers:{'accept-language':'en-AU,en;q=0.9'}}), env, now);
  assert.equal(english.status, 302);
  assert.equal(english.headers.get('location'), 'https://vesqen.sumirenokai.com/activate/');
  const chinese = await handle(new Request('https://example.invalid/activate/', {headers:{'accept-language':'zh-CN,zh;q=0.9,en;q=0.8'}}), env, now);
  assert.equal(chinese.headers.get('location'), 'https://vesqen.sumirenokai.com/zh/activate/');
  assert.equal((await handle(new Request('https://example.invalid/activate', {method:'POST'}), env, now)).status, 405);
});
