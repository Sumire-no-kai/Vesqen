import {readBoundedText} from './validation.js';

const REPOSITORY = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100}$/;
const TAG = /^v[0-9A-Za-z.+-]{1,100}$/;
const APK = /^[0-9A-Za-z._+-]{1,200}\.apk$/;

/**
 * Reads GitHub's public download counters for the release APKs once per UTC day (owner decision,
 * 2026-10-08). The request carries no user data and no credentials; only the release tag, file
 * name and cumulative count are stored. Unset DOWNLOAD_COUNTS_REPO turns this off.
 */
export async function snapshotDownloads(env, now, fetcher = fetch) {
  const repository = env.DOWNLOAD_COUNTS_REPO;
  if (!repository) return;
  if (!REPOSITORY.test(repository)) throw new Error('invalid_download_counts_configuration');
  const day = new Date(now).toISOString().slice(0,10);
  // The hourly trigger retries a day that failed; a stored day is not fetched again.
  const stored = await env.DB.prepare('SELECT 1 AS present FROM daily_downloads WHERE day=? LIMIT 1').bind(day).all();
  if (stored.results.length) return;
  const response = await fetcher(`https://api.github.com/repos/${repository}/releases?per_page=100`, {
    redirect:'manual', signal:AbortSignal.timeout(10000),
    // GitHub rejects API requests without a User-Agent.
    headers:{Accept:'application/vnd.github+json','User-Agent':'vesqen-usage-worker','X-GitHub-Api-Version':'2022-11-28'},
  });
  if (response.status !== 200) throw new Error('download_counts_unavailable');
  const releases = JSON.parse(await readBoundedText(response.body, 4000000, 10000));
  if (!Array.isArray(releases)) throw new Error('download_counts_invalid');
  const rows = releases
    .filter(release => release?.draft === false && typeof release.tag_name === 'string' && TAG.test(release.tag_name)
      && Array.isArray(release.assets))
    .flatMap(release => release.assets
      .filter(asset => typeof asset?.name === 'string' && APK.test(asset.name)
        && Number.isSafeInteger(asset.download_count) && asset.download_count >= 0)
      .map(asset => [day, release.tag_name, asset.name, asset.download_count]));
  if (!rows.length) return;
  await env.DB.batch(rows.map(row => env.DB.prepare(`INSERT INTO daily_downloads(day,release_tag,asset,downloads)
    VALUES(?,?,?,?) ON CONFLICT(day,release_tag,asset) DO UPDATE SET downloads=excluded.downloads`).bind(...row)));
}
