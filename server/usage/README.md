# Usage and Device Report Worker

Part of #70 (server PR). No runtime package dependencies. Node 24+ and the pinned
Wrangler development dependency are used for tests, local D1 and packaging.
Nothing in this directory deploys automatically or contains real account IDs,
database IDs, service hostnames, API tokens or other credentials.

## Endpoints and data

- `POST /v1/usage`: JSON, at most 4 KiB, exact #70 client fields. Unknown keys,
  invalid types, encoded/compressed bodies and malformed JSON are rejected. No IP,
  device/install ID, cookie, user-agent or incoming authorization is inspected,
  hashed or stored. UTC day comes from the server, not the caller. D1 stores only
  daily totals and separate version, model, mixer-capability and model/capability
  counters. Android version, ROM and recent USB input are validated then discarded;
  they are not retained as raw records or combined into a device fingerprint.
- `POST /v1/reports`: JSON, at most 256 KiB. Validates the actual #69 schema (PR #83),
  including confidence, time, sources, dependency closure, reason codes and the
  field privacy allowlists. Optional filename is only a basename; no library,
  listening-history, path, Bluetooth-name, MAC or arbitrary exception-text fields.
  The response contains a random **report** receipt ID, not a device/client ID.
  Only the validated JSON document plus server receipt/expiry times are stored,
  not HTTP headers, IP or the raw HTTP request. Upload must be invoked only after
  #69's full preview/user confirmation. This PR does not silently enable its client
  uploader or add a public report-reading API.

Both endpoints have bounded streaming reads (5 seconds) and generic error codes.
No exception text is returned. Rate-limit bindings use only two fixed route keys:
120 usage/minute and 10 reports/minute per Cloudflare location. These limits are
approximate per-location controls, **not per-device or global rate limits**. Atomic
D1 CHECK constraints additionally cap accepted writes at 10,000 pings and 100
reports per UTC day globally. Batch failure rolls back every affected counter or
report. Change these SQL ceilings via a reviewed migration if growth warrants it.
Attackers can consume shared quotas or forge valid pings; figures are approximate.
No attempt is made to obtain a stable identity to prevent that.

`daily_active` sums `firstToday`. Sum `first_in_week` across an ISO Monday week and
`first_in_month` across a UTC calendar month for the corresponding activity
estimates. Client failures, reinstalls, clock changes and malicious requests can
undercount or overcount. They are not independently deduplicated unique-user counts.

## Existing updates (#68 / #78)

There is **no new version-file endpoint**. #78 already consumes/generated stable
and beta manifests at the owner's static website origin. Set
`UPDATE_MANIFEST_BASE_URL` to that existing HTTPS directory when it is published.
The Worker fetches only `stable.json` and `beta.json` with a 1-hour cache hint,
without forwarding any user headers or payload. Valid files are included under
`updateManifests` in the usage response for the client's existing updater seam.
A failed/unconfigured/oversized source simply yields no manifest and does not
undo accepted statistics. Each file is capped at 60,000 bytes so the combined
response stays below the Android 128 KiB bound. The client independently validates
and verifies updates. No release workflow, static-file generator or website is
modified here; the owner must still complete #68's static artifact publication.

## Retention and logs

Owner-selected defaults: **reports 7 days, daily aggregates 90 UTC date buckets**.
These are `REPORT_RETENTION_DAYS` (1–30) and `AGGREGATE_RETENTION_DAYS` (1–365).
The hourly scheduled handler deletes expired reports and old counts/dimensions;
owner queries hide expired reports immediately. Physical live-table removal may
lag by up to an hour, or longer if a scheduled run fails. Lowering report retention
also removes older existing rows on the next scheduled run. Monitor scheduled
success and storage size using aggregate platform metrics, without enabling logs.

Worker logs/traces and Wrangler metrics are explicitly disabled. There are no
console calls, Logpush, tail or analytics bindings. **Cloudflare still processes
network traffic**; account-level security features and D1 Time Travel/backups have
independent behavior/retention. The owner must review those settings and explain
app retention versus platform recovery windows in the policy. Do not promise that
this code controls Cloudflare's own network/security data. Restoring a D1 backup
requires immediately rerunning retention before querying reports.

## Local verification (no account needed)

```sh
cd server/usage
npm ci --ignore-scripts
npm test
npm run check
```

Tests cover real SQLite migrations/atomic rollback, a local Workers+D1 runtime,
strict schemas, #69 group combinations, privacy rejection, rate limits, retention,
owner-query injection rejection and static-update reuse. The catalog drift test
fails when Android adds/changes a metric; review its export policy and update the
server schema together. `npm run check` uses **dry-run**, not deployment. The test
harness creates disposable local databases and never contacts remote D1.

For manual local requests, run `npx wrangler d1 migrations apply vesqen-usage --local`
then `npm run dev`. Actual deployment compatibility and owner account settings are
not established by local tests.

## Owner setup and deployment (manual; no commands run by this PR)

1. Review/approve the public-document changes listed in both #70 PR descriptions,
   the regional consent policy and the limits/retention above. Connect the client
   explanation UI before enabling its endpoint. Check the quota/storage impact;
   a free plan is not a guarantee of unlimited abuse tolerance.
2. Authenticate locally with `npx wrangler login`, or supply a least-privileged
   Cloudflare token via environment/CI secret storage. Never commit credentials.
3. Create the database: `npx wrangler d1 create vesqen-usage`.
4. Copy `wrangler.jsonc` to the ignored `wrangler.local.jsonc`. Replace the zero UUID
   with the returned D1 UUID. Add `account_id: "YOUR_ACCOUNT_ID"` locally if needed;
   no real account value belongs in this repository. Choose unused numeric rate
   limit namespace IDs within your account; 1001/1002 are example placeholders.
5. In that local config set `routes` to
   `[{ "pattern": "YOUR_USAGE_SUBDOMAIN", "custom_domain": true }]` on your zone.
   Keep `workers_dev` and `preview_urls` false. Configure the existing static update
   directory if ready. Verify all observability/logging remains off.
6. Apply remote migrations explicitly:
   `npx wrangler d1 migrations apply vesqen-usage --remote --config wrangler.local.jsonc`.
7. Deploy explicitly: `npx wrangler deploy --config wrangler.local.jsonc`.
   Verify the custom domain, TLS, D1 binding and scheduled trigger. Perform a
   synthetic ping/report acceptance test, expiry test and endpoint/limit checks.
8. Configure the Android build's `vesqen.usageEndpoint` to
   `https://YOUR_USAGE_SUBDOMAIN/v1/usage`. A future #69 uploader posts its exact
   previewed bytes to `/v1/reports`. No client secret or new public identifier is
   needed or useful for an open-source anonymous endpoint.

## Owner-only queries

These use Wrangler/D1 account authentication; there is no public admin endpoint.
They default to local storage. Add `--remote --config=wrangler.local.jsonc` only
when intentionally querying the owner's live database.

```sh
npm run query -- daily 2026-10-04
npm run query -- reports
npm run query -- report 12345678-1234-1234-1234-123456789abc
```

`reports` lists up to 50 unexpired receipts without documents; `report UUID` reads
one unexpired document. Treat opted-in filenames/report text as private: do not
paste query output into issues, public logs or analytics tools. The script validates
all SQL-interpolated values and returns JSON, not rendered HTML.
