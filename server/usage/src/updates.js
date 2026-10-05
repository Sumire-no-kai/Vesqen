import {readBoundedText, requireValid as check, integer} from './validation.js';

/** Like the app's UpdateManifest parser: required keys must be valid, later additions are ignored. */
function has(value, keys) {
  check(value !== null && typeof value === 'object' && !Array.isArray(value) && keys.every(key => Object.hasOwn(value, key)));
}

function validateManifest(value, channel) {
  has(value, ['schemaVersion','channel','release']);
  check(value.schemaVersion === 1 && value.channel === channel);
  if (value.release === null) return value;
  const r = value.release;
  has(r, ['versionName','versionCode','minimumAndroidApi','apkUrls','sha256','releaseNotes']);
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
  has(r.releaseNotes,['en','zh-CN']);
  for (const key of ['en','zh-CN']) check(typeof r.releaseNotes[key] === 'string' && r.releaseNotes[key].length >= 1 && r.releaseNotes[key].length <= 32768);
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
      // Workers fetch accepts only 'follow' or 'manual'; a manual redirect is not 200 and is dropped.
      // 2.5 s leaves room for D1 inside the app's 5 s read timeout.
      const response = await fetcher(new URL(`${channel}.json`, url).href, {
        redirect:'manual', signal:AbortSignal.timeout(2500),
        headers:{Accept:'application/json'}, cf:{cacheTtl:3600, cacheEverything:true},
      });
      if (response.status !== 200) return null;
      // An upstream response is not a client request: Workers already decoded any gzip while
      // keeping its Content-Encoding header, so only the size bound applies here. Each channel is
      // bounded so both plus the wrapper fit the client's 128 KiB response limit.
      const manifest = validateManifest(JSON.parse(await readBoundedText(response.body, 60000)), channel);
      return [channel, manifest];
    } catch { return null; } // Optional upstream failure cannot discard an already accepted ping.
  }));
  return Object.fromEntries(pairs.filter(Boolean));
}
