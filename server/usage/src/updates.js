import {readJson, object, requireValid as check, integer} from './validation.js';

function validateManifest(value, channel) {
  object(value, ['schemaVersion','channel','release']);
  check(value.schemaVersion === 1 && value.channel === channel);
  if (value.release === null) return value;
  const r = value.release;
  object(r, ['versionName','versionCode','minimumAndroidApi','apkUrls','sha256','releaseNotes']);
  check(typeof r.versionName === 'string' && r.versionName.length <= 100 && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$/.test(r.versionName));
  check(channel !== 'stable' || !r.versionName.includes('-'));
  check(integer(r.versionCode) && r.versionCode > 0 && r.versionCode <= 2100000000);
  check(integer(r.minimumAndroidApi) && r.minimumAndroidApi >= 26 && r.minimumAndroidApi <= 1000);
  check(typeof r.sha256 === 'string' && /^[0-9a-f]{64}$/.test(r.sha256));
  check(Array.isArray(r.apkUrls) && r.apkUrls.length >= 1 && r.apkUrls.length <= 5);
  for (const raw of r.apkUrls) {
    check(typeof raw === 'string' && raw.length <= 4096);
    const url = new URL(raw);check(url.protocol === 'https:' && !url.username && !url.password && !url.hash);
  }
  object(r.releaseNotes,['en','zh-CN']);
  for (const text of Object.values(r.releaseNotes)) check(typeof text === 'string' && text.length >= 1 && text.length <= 32768);
  return value;
}

/** Reuse #78's owner-configured static origin. Never forward any incoming headers or payload. */
export async function updateManifests(base, fetcher = fetch) {
  if (!base) return {};
  let url;
  try { url = new URL(base); } catch { return {}; }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !url.pathname.endsWith('/')) return {};
  const pairs = await Promise.all(['stable','beta'].map(async channel => {
    try {
      const response = await fetcher(new URL(`${channel}.json`, url).href, {
        redirect:'error', signal:AbortSignal.timeout(4000),
        headers:{Accept:'application/json'}, cf:{cacheTtl:3600, cacheEverything:true},
      });
      if (response.status !== 200) return null;
      // Bound each channel so both plus the wrapper fit the client's 128 KiB response limit.
      const manifest = validateManifest(await readJson(response, 60000), channel);
      return [channel, manifest];
    } catch { return null; } // Optional upstream failure cannot discard an already accepted ping.
  }));
  return Object.fromEntries(pairs.filter(Boolean));
}
