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
- **Activation links.** `https://vesqen.sumirenokai.com/activate#<code>` opens
  `ActivationLinkActivity`, which checks the code and opens the Pro features page. The code sits in
  the fragment, which browsers do not send to the server. The site's `/.well-known/assetlinks.json`
  must list the app signing certificate for Android to open the link in the app.
- **Storage.** The kept code is stored in the app's private storage and checked again at every
  start. A revoked serial or a key the build no longer trusts removes the unlock. The app never
  contacts a server to activate.
- **Revocation list.** `app/src/github/assets/activation/revoked.txt` has one `keyId:serial` per
  line and ships with each version.

## Server side (planned, #120)

The Worker takes Stripe's `checkout.session.completed` event and assigns a pool code to the order.
Refund and dispute events mark the code revoked, and the next app version adds it to the
revocation list. The success page asks the Worker for the order's code after Stripe confirms the
payment. The Worker stores only the Stripe order id, the code's key id and serial, its state and
times; no email, name or IP. That part, its D1 tables and the website pages are not built yet.
