import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

export function querySql(command, value) {
  if (command === 'daily') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? '') || new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) !== value)
      throw new Error('daily requires YYYY-MM-DD');
    return `SELECT * FROM daily_totals WHERE day='${value}'; SELECT * FROM daily_dimensions WHERE day='${value}' ORDER BY dimension,value;`;
  }
  if (command === 'reports' && value === undefined)
    return "SELECT id,received_at,expires_at,length(document) AS bytes FROM reports WHERE expires_at > unixepoch()*1000 ORDER BY received_at DESC LIMIT 50;";
  if (command === 'report' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value ?? ''))
    return `SELECT id,received_at,expires_at,document FROM reports WHERE id='${value}' AND expires_at > unixepoch()*1000;`;
  throw new Error('Use daily YYYY-MM-DD, reports, or report UUID');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const args=process.argv.slice(2);
    const remote=args.includes('--remote');
    const configArg=args.find(value=>value.startsWith('--config='));
    const positional=args.filter(value=>value!=='--remote'&&!value.startsWith('--config='));
    if (positional.length>2) throw new Error('Too many arguments');
    const sql=querySql(...positional);
    const config=configArg?.slice('--config='.length) ?? 'wrangler.jsonc';
    const cli=fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js',import.meta.url));
    const result=spawnSync(process.execPath,[cli,'d1','execute','vesqen-usage','--config',config,remote?'--remote':'--local','--command',sql,'--json'],
      {cwd:fileURLToPath(new URL('../',import.meta.url)),stdio:'inherit',env:{...process.env,WRANGLER_SEND_METRICS:'false'}});
    if (result.error) throw result.error;
    process.exitCode=result.status ?? 1;
  } catch (error) {process.stderr.write(`${error.message}\n`);process.exitCode=1;}
}
