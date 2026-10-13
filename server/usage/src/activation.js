import {readBoundedText} from './validation.js';

/**
 * #120: website sales of activation codes (docs/ACTIVATION_CODES.md).
 *
 * - POST /v1/stripe/webhook takes Stripe's events: a paid Checkout Session gets the next code from
 *   the pool; a full refund or a dispute revokes the order's code.
 * - GET /v1/activation?session=<Checkout Session id> is how the purchase page shows the code. When
 *   the event has not arrived yet, the Worker asks Stripe whether the session is paid.
 *
 * Every step is idempotent, so Stripe's retries and out-of-order events cannot hand out a second
 * code or revoke the wrong one. Only order and payment ids are stored, never buyer details.
 */
const SESSION = /^cs_(?:test|live)_[A-Za-z0-9]{10,200}$/;
const PAYMENT_INTENT = /^pi_[A-Za-z0-9]{10,200}$/;
const SIGNATURE_TOLERANCE_SECONDS = 300;

function reply(status, value, extra = {}) {
  return new Response(JSON.stringify(value), {
    status, headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff', ...extra},
  });
}

export const isActivationPath = path => path === '/v1/stripe/webhook' || path === '/v1/activation';

export async function handleActivation(request, env, now, fetcher) {
  const url = new URL(request.url);
  // Off unless set to "open": sales stay closed until the owner configures them. "closed" pauses
  // delivery at once; Stripe retries its events later, so none is lost.
  if (env.ACTIVATION_INGEST !== 'open') return reply(503, {error:'SERVICE_PAUSED'});
  if (url.pathname === '/v1/stripe/webhook') {
    if (request.method !== 'POST') return reply(405, {error:'METHOD_NOT_ALLOWED'});
    return webhook(request, env, now);
  }
  const cors = {'access-control-allow-origin':env.ACTIVATION_ORIGIN, vary:'origin'};
  if (request.method !== 'GET') return reply(405, {error:'METHOD_NOT_ALLOWED'}, cors);
  const session = url.searchParams.get('session');
  if ([...url.searchParams.keys()].length !== 1 || !SESSION.test(session ?? '')) return reply(400, {error:'INVALID_REQUEST'}, cors);
  if (!(await env.ACTIVATION_LIMITER.limit({key:'activation'})).success) return reply(429, {error:'RATE_LIMITED'}, cors);
  let found = await codeFor(env, session);
  if (!found) {
    // The event may still be on its way; Stripe's own record decides whether this order is paid.
    const checkout = await stripeSession(env, session, fetcher);
    if (checkout === null) return reply(404, {error:'NOT_FOUND'}, cors);
    if (!paid(checkout)) return reply(409, {error:'NOT_PAID'}, cors);
    found = await assign(env, checkout, now);
    if (!found) return reply(503, {error:'NO_CODES_LEFT'}, cors);
  }
  if (found.revoked_at !== null) return reply(410, {error:'REVOKED'}, cors);
  return reply(200, {code:found.code}, cors);
}

async function webhook(request, env, now) {
  if (!env.STRIPE_WEBHOOK_SECRET) return reply(503, {error:'SERVICE_UNAVAILABLE'});
  const payload = await readBoundedText(request.body, 262144);
  if (!(await signed(payload, request.headers.get('stripe-signature'), env.STRIPE_WEBHOOK_SECRET, now)))
    return reply(400, {error:'INVALID_SIGNATURE'});
  const event = JSON.parse(payload);
  const object = event?.data?.object ?? {};
  switch (event?.type) {
    case 'checkout.session.completed':
    case 'checkout.session.async_payment_succeeded':
      if (!paid(object)) break;
      // An empty pool fails the delivery so Stripe retries once the owner adds codes.
      if (!(await assign(env, object, now))) return reply(503, {error:'NO_CODES_LEFT'});
      break;
    case 'charge.refunded':
      // Partial refunds keep the license; only a full refund revokes it.
      if (object.refunded === true) await revoke(env, object.payment_intent, 'refund', now);
      break;
    case 'charge.dispute.created':
      await revoke(env, object.payment_intent, 'dispute', now);
      break;
    default:
      break;
  }
  return reply(200, {received:true});
}

function paid(session) {
  return SESSION.test(session?.id ?? '') && session.mode === 'payment' && session.payment_status === 'paid';
}

async function codeFor(env, order) {
  const {results} = await env.DB.prepare('SELECT code, revoked_at FROM activation_codes WHERE order_id=?').bind(order).all();
  return results[0] ?? null;
}

/** One statement takes the next free code for an order that has none; a repeat does nothing. */
async function assign(env, session, now) {
  const paymentIntent = PAYMENT_INTENT.test(session.payment_intent ?? '') ? session.payment_intent : null;
  await env.DB.batch([env.DB.prepare(`UPDATE activation_codes SET order_id=?1, payment_intent=?2, assigned_at=?3
    WHERE (key_id, serial) = (SELECT key_id, serial FROM activation_codes
      WHERE order_id IS NULL AND revoked_at IS NULL ORDER BY key_id, serial LIMIT 1)
    AND NOT EXISTS (SELECT 1 FROM activation_codes WHERE order_id=?1)`).bind(session.id, paymentIntent, now)]);
  return codeFor(env, session.id);
}

async function revoke(env, paymentIntent, reason, now) {
  if (!PAYMENT_INTENT.test(paymentIntent ?? '')) return;
  // The first reason stands; a dispute after a refund does not rewrite the record.
  await env.DB.batch([env.DB.prepare(`UPDATE activation_codes SET revoked_at=?2, revoke_reason=?3
    WHERE payment_intent=?1 AND revoked_at IS NULL`).bind(paymentIntent, now, reason)]);
}

async function stripeSession(env, id, fetcher) {
  if (!env.STRIPE_SECRET_KEY) throw new Error('stripe_secret_missing');
  const response = await fetcher(`https://api.stripe.com/v1/checkout/sessions/${id}`, {
    redirect:'manual', signal:AbortSignal.timeout(10000),
    headers:{Authorization:`Bearer ${env.STRIPE_SECRET_KEY}`},
  });
  if (response.status === 404) return null;
  if (response.status !== 200) throw new Error('stripe_unavailable');
  return JSON.parse(await readBoundedText(response.body, 262144, 10000));
}

/** Stripe-Signature: `t=<seconds>,v1=<hex HMAC-SHA256 of "t.payload">`, possibly several v1. */
async function signed(payload, header, secret, now) {
  if (!header) return false;
  const fields = header.split(',').map(part => part.split('='));
  const timestamp = Number(fields.find(([name]) => name === 't')?.[1]);
  const candidates = fields.filter(([name]) => name === 'v1').map(([, value]) => value);
  if (!Number.isInteger(timestamp) || Math.abs(now / 1000 - timestamp) > SIGNATURE_TOLERANCE_SECONDS || !candidates.length) return false;
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), {name:'HMAC', hash:'SHA-256'}, false, ['sign']);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(`${timestamp}.${payload}`)));
  const expected = [...mac].map(byte => byte.toString(16).padStart(2, '0')).join('');
  return candidates.some(candidate => sameText(candidate, expected));
}

function sameText(a, b) {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}
