# Activation codes

Website purchases unlock the GitHub build with an activation code (#120). The decisions behind
this, the purchase flow and the Stripe constraints are in [MONETIZATION.md](MONETIZATION.md);
this page is the technical record. The Play build never takes codes; it sells through Google Play.

## What a code is

A code is 73 bytes written in [Crockford base32](https://www.crockford.com/base32.html) as 117
characters, shown in groups of five:

| Bytes | Field | Notes |
| --- | --- | --- |
| 0 | Format version | `1` |
| 1 | Key id | `1`–`127` production keys, `129` (`0x81`) the debug test key |
| 2 | Product | `1` = Pro features, including ones added later |
| 3–6 | Serial | Unsigned 32-bit, big-endian, starting at 1 per key |
| 7–8 | Issue day | Days since 2026-01-01 UTC, unsigned 16-bit |
| 9–72 | Signature | ECDSA P-256 over SHA-256 of `"vesqen.activation.v1\n"` followed by bytes 0–8, as r ‖ s |

ECDSA P-256 is the algorithm the app already uses for output verification records. Android 8,
the minimum version, verifies it without an extra library. Ed25519 would need one before Android 13.

Parsing ignores spaces, dashes and line breaks, is case-insensitive, and reads `O` as `0` and `I`
or `L` as `1`. A nonzero padding bit in the last character makes the code invalid.

The owner's tool (`server/usage/src/activation-code.js`) and the app
(`app/src/github/.../premium/ActivationCodeVerifier.kt`) implement the format separately. A shared
test vector, signed by a throwaway key whose private half was deleted, keeps them equal: both
test suites verify the same two codes.

## Why codes never collide

- **Serials are unique per key.** The tool hands them out in order and records the last one in
  a ledger next to the private key. It refuses to issue without the ledger (except `--init` for a
  brand-new key), to overwrite a ledger with another key's codes, or to overwrite an output file.
- **Codes cannot be forged.** Without the private key, no change to a code passes the signature
  check, so a serial cannot be invented or edited.
- **One order gets one code** (Worker, not built yet). In a single atomic statement, the Worker
  assigns the next unassigned code in its pool to an order. The order id is unique, so a retried
  Stripe event gets the same code back, and two orders can never get the same one.

The app checks codes offline and cannot tell whether someone else uses the same code. A leaked or
refunded code is revoked instead; see below.

## Keys and custody

- **Production keys.** The owner generates them on the release Mac with
  `node scripts/activation-codes.js keygen --key-id 1 --out <path>` (run in `server/usage`) and
  keeps the private key with the release signing keys under
  `~/Library/Application Support/Vesqen/keys/`, inside the same encrypted backups
  ([RELEASE_SIGNING.md](RELEASE_SIGNING.md)). The private key never goes to the Worker, the
  repository, CI or chat. The printed public key goes into `ActivationKeys.production` under its
  key id. None exists yet; the first is made before 1.0. Retired keys stay in the app so sold
  codes keep working.
- **Debug test key.** Key id 129, trusted only by debug builds. Its private key and ledger live
  in `~/Library/Application Support/Vesqen/activation-test/` on the development Mac. Codes it
  signs exist only for testing with Stripe's test mode and never reach buyers.

## Issuing

```bash
cd server/usage
node scripts/activation-codes.js issue --key <private.pem> --key-id 1 \
  --ledger <ledger.json> --count 500 --out <codes.csv>
```

The output holds `serial,code` rows and is written with owner-only permissions. Upload it to the
Worker's pool when that part exists. Treat unassigned codes like stock: anyone who copies them can
use them, so a pool that may have leaked is revoked as a batch.

## In the app

- **Where entry appears.** The GitHub build shows the activation section on the Pro features page
  only in builds that charge (`vesqen.premiumEnforced`) and in debug builds. The betas' release
  builds take no codes.
- **Activation links.** `https://vesqen-api.sumirenokai.com/activate#<code>` opens
  `ActivationLinkActivity`, which checks the code and opens the Pro features page. Any app or page
  can open such a link, so a link never replaces a code that is already active (#194 S3). The Pro
  features page takes a typed code only while no code works, so an active code cannot be replaced
  yet; a way to enter another code is due before sales open (#194, round 3 B5). The code sits in
  the fragment, which browsers do not send to the server. The link uses the API host on purpose:
  Chrome keeps a link that stays on the current site in the browser, so a link on
  `vesqen.sumirenokai.com` could never leave the website's purchase page for the app. The Worker
  serves the `/.well-known/assetlinks.json` that lets Android verify the link, and sends a browser
  that does open it (no app, or an unverified link) on to the website's `/activate/` page, which
  shows the code for pasting.
- **Storage.** The kept code is stored in the app's private storage and checked again at every
  start. A revoked serial or a key the build no longer trusts removes the unlock. The app never
  contacts a server to activate.
- **Revocation list.** `app/src/github/assets/activation/revoked.txt` has one `keyId:serial` per
  line and ships with each version.

## Server side

The usage Worker (`server/usage`) delivers codes; it never signs them.

- **Pool.** Issued codes go into the `activation_codes` table (migration `0003`). Upload them with
  `node scripts/activation-codes.js pool-sql --key-id 1 --codes <codes.csv> --out <pool.sql>`, then
  `npx wrangler d1 execute vesqen-usage --remote --file <pool.sql>`. A repeated upload changes nothing.
- **`POST /v1/stripe/webhook`.**
  - Checks Stripe's signature (`STRIPE_WEBHOOK_SECRET`, five-minute tolerance).
  - A paid `checkout.session.completed` or `checkout.session.async_payment_succeeded` takes the next
    free code for that order. When the pool is empty it answers 503, so Stripe retries after more
    codes are uploaded.
  - A full `charge.refunded` revokes the order's code with reason `refund`; `charge.dispute.created`
    revokes it with reason `dispute`. The first reason stands, and a partial refund keeps the code.
- **`GET /v1/activation?session=<Checkout Session id>`.** The purchase page's request, readable only
  from `ACTIVATION_ORIGIN`.
  - Returns the order's code, or `410` when it was revoked.
  - When the event has not arrived yet, it asks Stripe whether the session is paid, using
    `STRIPE_SECRET_KEY`, a restricted key that can only read Checkout Sessions. A paid session then
    gets its code; an unpaid one gets `409`.
  - Rate-limited under one fixed key.
- **Switch.** `ACTIVATION_INGEST` must be `open`; unset or any other value answers 503 before
  anything is read. It ships `closed` until the owner opens sales.
- **Stored.** The Checkout Session id and payment intent id of the order, the code's key id and
  serial, and when it was assigned or revoked. No email, name, address or IP. Rows are kept as the
  license record.

Every step is idempotent. A retried or late event finds its order already served. Two orders can
never take the same code, because one statement takes the lowest free code for an order that has none.

### Owner tasks

- **Lost code.** Find the order in the Stripe Dashboard by the buyer's email, copy its Checkout
  Session id (`cs_...`), then run
  `npx wrangler d1 execute vesqen-usage --remote --command "SELECT code FROM activation_codes WHERE order_id='cs_...'"`.
- **Revocations for the next release.** Run
  `SELECT key_id || ':' || serial FROM activation_codes WHERE revoked_at IS NOT NULL ORDER BY 1`
  and add new lines to `revoked.txt`. A leaked code is revoked by hand with `revoke_reason='leak'`.
