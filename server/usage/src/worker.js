import {InvalidRequest, readJson, validatePing} from './validation.js';
import {validateReport} from './report-schema.js';
import {updateManifests} from './updates.js';

const DAY = 86400000;
const response = (status, value) => new Response(JSON.stringify(value), {
  status, headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'},
});
function retention(value, maximum) {
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > maximum) throw new Error('invalid_retention_configuration');
  return Number(value);
}
export async function prune(env, now) {
  const reportDays = retention(env.REPORT_RETENTION_DAYS, 30);
  const aggregateDays = retention(env.AGGREGATE_RETENTION_DAYS, 365);
  const cutoff = new Date(now - (aggregateDays - 1) * DAY).toISOString().slice(0,10);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM reports WHERE expires_at <= ? OR received_at <= ?').bind(now, now - reportDays * DAY),
    env.DB.prepare('DELETE FROM daily_dimensions WHERE day < ?').bind(cutoff),
    env.DB.prepare('DELETE FROM daily_totals WHERE day < ?').bind(cutoff),
    env.DB.prepare('DELETE FROM daily_report_counts WHERE day < ?').bind(cutoff),
  ]);
}
async function incrementUsage(env, ping, day) {
  const capability = ping.bitPerfectMixer === null ? 'unknown' : ping.bitPerfectMixer ? 'supported' : 'not_supported';
  // Separate dimensions deliberately avoid storing the full version/model/ROM tuple or raw ping.
  const dimensions = [
    ['version', JSON.stringify([ping.channel,ping.appVersion])],
    ['model', JSON.stringify([ping.manufacturer,ping.model])],
    ['bit_perfect',capability],
    ['model_bit_perfect',JSON.stringify([ping.manufacturer,ping.model,capability])],
  ];
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO daily_totals(day,requests,daily_active,first_in_week,first_in_month) VALUES(?,1,?,?,?)
      ON CONFLICT(day) DO UPDATE SET requests=requests+1,daily_active=daily_active+excluded.daily_active,
      first_in_week=first_in_week+excluded.first_in_week,first_in_month=first_in_month+excluded.first_in_month`)
      .bind(day, Number(ping.firstToday), Number(ping.firstThisWeek), Number(ping.firstThisMonth)),
    ...dimensions.map(([dimension,value]) => env.DB.prepare(`INSERT INTO daily_dimensions(day,dimension,value,count) VALUES(?,?,?,1)
      ON CONFLICT(day,dimension,value) DO UPDATE SET count=count+1`).bind(day,dimension,value)),
  ]);
}

export async function handle(request, env, now = Date.now(), fetcher = fetch) {
  try {
    const url = new URL(request.url);
    if (!['/v1/usage','/v1/reports'].includes(url.pathname)) return response(404,{error:'NOT_FOUND'});
    if (request.method !== 'POST') return response(405,{error:'METHOD_NOT_ALLOWED'});
    if (url.search || url.hash) return response(400,{error:'INVALID_REQUEST'});
    const reportDays = retention(env.REPORT_RETENTION_DAYS,30);
    retention(env.AGGREGATE_RETENTION_DAYS,365);
    const isUsage = url.pathname === '/v1/usage';
    // Fixed route keys only. Never read/store/hash IP, cookies, authorization or client identifiers.
    const limiter = isUsage ? env.USAGE_LIMITER : env.REPORT_LIMITER;
    if (!(await limiter.limit({key:isUsage ? 'usage' : 'reports'})).success) return response(429,{error:'RATE_LIMITED'});
    const document = await readJson(request, isUsage ? 4096 : 262144);
    const validated = isUsage ? validatePing(document) : validateReport(document);
    const day = new Date(now).toISOString().slice(0,10);
    if (isUsage) {
      await incrementUsage(env,validated,day);
      return response(200,{updateManifests:await updateManifests(env.UPDATE_MANIFEST_BASE_URL, fetcher)});
    }
    const id = crypto.randomUUID(); // Per-report receipt, never supplied by or linked to a client ID.
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO daily_report_counts(day,count) VALUES(?,1) ON CONFLICT(day) DO UPDATE SET count=count+1`).bind(day),
      env.DB.prepare('INSERT INTO reports(id,received_at,expires_at,document) VALUES(?,?,?,?)')
        .bind(id,now,now+reportDays*DAY,JSON.stringify(validated)),
    ]);
    return response(201,{reportId:id});
  } catch (error) {
    if (error instanceof InvalidRequest) return response(error.status,{error:'INVALID_REQUEST'});
    // A hard daily CHECK quota aborts the entire D1 batch, including dimension/report inserts.
    if (/CHECK constraint failed: (requests BETWEEN 0 AND 10000|count BETWEEN 0 AND 100)/.test(String(error?.message)))
      return response(429,{error:'DAILY_LIMIT_REACHED'});
    return response(503,{error:'SERVICE_UNAVAILABLE'});
  }
}

export default {
  fetch(request, env) { return handle(request, env); },
  async scheduled(_event, env) { await prune(env, Date.now()); },
};
