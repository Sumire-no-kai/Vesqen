import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function utcDate(value, name) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? '') || new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) !== value)
    throw new Error(`${name} requires YYYY-MM-DD`);
  return value;
}

export function querySql(command, value) {
  if (command === 'daily') {
    utcDate(value, 'daily');
    return `SELECT * FROM daily_totals WHERE day='${value}'; SELECT * FROM daily_dimensions WHERE day='${value}' ORDER BY dimension,value;`;
  }
  if (command === 'weekly') {
    utcDate(value, 'weekly');
    if (new Date(`${value}T00:00:00Z`).getUTCDay() !== 1) throw new Error('weekly requires the Monday of an ISO week');
    const end = new Date(Date.parse(`${value}T00:00:00Z`) + 6 * 86400000).toISOString().slice(0,10);
    return `SELECT '${value}' AS week_start, SUM(first_in_week) AS weekly_active, SUM(requests) AS requests FROM daily_totals WHERE day BETWEEN '${value}' AND '${end}';`;
  }
  if (command === 'monthly') {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(value ?? '')) throw new Error('monthly requires YYYY-MM');
    return `SELECT '${value}' AS month, SUM(first_in_month) AS monthly_active, SUM(requests) AS requests FROM daily_totals WHERE substr(day,1,7)='${value}';`;
  }
  if (command === 'downloads' && value === undefined)
    return `SELECT day, release_tag, asset, downloads,
      downloads - LAG(downloads) OVER (PARTITION BY release_tag, asset ORDER BY day) AS since_previous_snapshot
      FROM daily_downloads ORDER BY day DESC, release_tag DESC, asset LIMIT 300;`;
  if (command === 'report-counts' && value === undefined)
    return 'SELECT day,count FROM daily_report_counts ORDER BY day DESC LIMIT 30;';
  if (command === 'reports' && value === undefined)
    return "SELECT id,received_at,expires_at,length(document) AS bytes FROM reports WHERE expires_at > unixepoch()*1000 ORDER BY received_at DESC LIMIT 50;";
  if (command === 'report' && UUID.test(value ?? ''))
    return `SELECT id,received_at,expires_at,document FROM reports WHERE id='${value}' AND expires_at > unixepoch()*1000;`;
  if (command === 'delete-report' && UUID.test(value ?? ''))
    return `DELETE FROM reports WHERE id='${value}';`;
  throw new Error('Use daily YYYY-MM-DD, weekly YYYY-MM-DD (Monday), monthly YYYY-MM, downloads, report-counts, reports, report UUID or delete-report UUID');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const args=process.argv.slice(2);
    const remote=args.includes('--remote');
    const configArg=args.find(value=>value.startsWith('--config='));
    const databaseArg=args.find(value=>value.startsWith('--database='));
    const positional=args.filter(value=>value!=='--remote'&&!value.startsWith('--config=')&&!value.startsWith('--database='));
    if (positional.length>2) throw new Error('Too many arguments');
    const sql=querySql(...positional);
    const config=configArg?.slice('--config='.length) ?? 'wrangler.jsonc';
    const cli=fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js',import.meta.url));
    const database=databaseArg?.slice('--database='.length) ?? 'vesqen-usage';
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(database)) throw new Error('Invalid database name');
    const result=spawnSync(process.execPath,[cli,'d1','execute',database,'--config',config,remote?'--remote':'--local','--command',sql,'--json'],
      {cwd:fileURLToPath(new URL('../',import.meta.url)),stdio:'inherit',env:{...process.env,WRANGLER_SEND_METRICS:'false'}});
    if (result.error) throw result.error;
    process.exitCode=result.status ?? 1;
  } catch (error) {process.stderr.write(`${error.message}\n`);process.exitCode=1;}
}
