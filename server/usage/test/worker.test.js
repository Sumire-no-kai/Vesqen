import {test} from 'node:test';
import assert from 'node:assert/strict';
import {handle,prune} from '../src/worker.js';
import {DISTINCT_VALUES_PER_DAY} from '../src/limits.js';
import {environment} from './database.js';
import {ping,report,request} from './fixtures.js';
const now=Date.parse('2026-10-04T12:00:00Z');

test('usage persists only separate daily aggregates, never IP or the raw ping',async t => {
  const env=environment();t.after(()=>env.DB.close());
  assert.equal((await handle(request(ping(),'/v1/usage',{'cf-connecting-ip':'192.0.2.1','user-agent':'PrivateAgent','cookie':'private=secret'}),env,now)).status,200);
  assert.equal((await handle(request({...ping(),firstThisWeek:false,firstThisMonth:false}),env,now)).status,200);
  assert.deepEqual(env.DB.rows('SELECT * FROM daily_totals'),[{day:'2026-10-04',requests:2,daily_active:2,first_in_week:1,first_in_month:1}]);
  const dimensions=env.DB.rows('SELECT * FROM daily_dimensions');
  // Every field the client sends is counted in its own dimension; none is stored per request.
  assert.deepEqual(dimensions.map(row=>row.dimension).sort(),['android','bit_perfect','model','model_bit_perfect','recent_usb_audio','rom','version']);
  assert.ok(dimensions.every(row=>row.count===2));
  assert.ok(!JSON.stringify(dimensions).includes('romBuild'));
  assert.ok(!JSON.stringify(dimensions).includes('192.0.2.1'));
  assert.ok(!JSON.stringify(dimensions).includes('firstToday'));
  assert.deepEqual(env.calls,[{key:'usage'},{key:'usage'}]);
  assert.equal(env.DB.rows('SELECT * FROM reports').length,0);
});

test('strict payload rejects extra identifiers wrong types and oversized streamed bodies',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  for (const payload of [{...ping(),deviceId:'id'},{...ping(),firstToday:'true'},{...ping(),bitPerfectMixer:'verified'},
    {...ping(),model:'/private/song.flac'},{...ping(),model:'12:34:56:78:9A:BC'},{...ping(),firstToday:false}])
    assert.equal((await handle(request(payload),env,now)).status,400);
  assert.equal((await handle(request({...ping(),model:'x'.repeat(5000)}),env,now)).status,413);
  const stream=new ReadableStream({start(controller){controller.enqueue(new Uint8Array(4097));controller.close();}});
  assert.equal((await handle(new Request('https://example.invalid/v1/usage',{method:'POST',body:stream,duplex:'half',headers:{'content-type':'application/json'}}),env,now)).status,413);
  assert.equal(env.DB.rows('SELECT * FROM daily_totals').length,0);
});

test('unsupported media malformed JSON encoding method query and route cannot persist',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  assert.equal((await handle(request(ping(),'/v1/usage',{'content-type':'text/plain'}),env,now)).status,415);
  assert.equal((await handle(request(ping(),'/v1/usage',{'content-encoding':'gzip'}),env,now)).status,415);
  assert.equal((await handle(new Request('https://example.invalid/v1/usage',{method:'POST',body:'{',headers:{'content-type':'application/json'}}),env,now)).status,400);
  assert.equal((await handle(new Request('https://example.invalid/v1/usage'),env,now)).status,405);
  assert.equal((await handle(request(ping(),'/v1/usage?ip=secret'),env,now)).status,400);
  assert.equal((await handle(request(ping(),'/reports'),env,now)).status,404);
  assert.equal(env.DB.rows('SELECT * FROM daily_totals').length,0);
});

test('rate limit precedes parsing and uses fixed route keys',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  env.USAGE_LIMITER.limit=async value=>{assert.deepEqual(value,{key:'usage'});return {success:false};};
  assert.equal((await handle(request(),env,now)).status,429);
  assert.equal(env.DB.rows('SELECT * FROM daily_totals').length,0);
});

test('daily hard caps atomically roll back totals dimensions and report writes',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  env.DB.db.exec("INSERT INTO daily_totals VALUES('2026-10-04',10000,10000,0,0)");
  assert.equal((await handle(request(),env,now)).status,429);
  assert.equal(env.DB.rows('SELECT * FROM daily_dimensions').length,0);
  env.DB.db.exec("INSERT INTO daily_report_counts VALUES('2026-10-04',100)");
  assert.equal((await handle(request(report(),'/v1/reports'),env,now)).status,429);
  assert.equal(env.DB.rows('SELECT * FROM reports').length,0);
});

test('report is private validated document with a per-report receipt and seven-day expiry',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  const response=await handle(request(report(),'/v1/reports'),env,now);
  assert.equal(response.status,201);
  const {reportId}=await response.json();
  const rows=env.DB.rows('SELECT * FROM reports');
  assert.equal(rows[0].id,reportId);assert.equal(rows[0].expires_at,now+7*86400000);
  assert.deepEqual(JSON.parse(rows[0].document),report());
  // The stored document is the exact text the user previewed, not a re-serialisation.
  const spaced=JSON.stringify(report(),null,2);
  await handle(new Request('https://example.invalid/v1/reports',{method:'POST',body:spaced,headers:{'content-type':'application/json'}}),env,now);
  assert.ok(env.DB.rows('SELECT document FROM reports').some(row=>row.document===spaced));
  assert.equal((await handle(new Request(`https://example.invalid/v1/reports/${reportId}`),env,now)).status,404);
  assert.equal((await handle(new Request('https://example.invalid/v1/reports'),env,now)).status,405);
});

test('scheduled retention removes expired reports and old dimensions atomically',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  await handle(request(report(),'/v1/reports'),env,now);
  await handle(request(),env,now);
  await prune(env,now+7*86400000);
  assert.equal(env.DB.rows('SELECT * FROM reports').length,0);
  assert.equal(env.DB.rows('SELECT * FROM daily_totals').length,1);
  await prune(env,now+90*86400000);
  for (const table of ['daily_totals','daily_dimensions','daily_dimension_sizes','daily_report_counts']) assert.equal(env.DB.rows(`SELECT * FROM ${table}`).length,0);
});

test('forged distinct values fold into other once a dimension is full for the day',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  env.DB.db.exec(`INSERT INTO daily_totals VALUES('2026-10-04',0,0,0,0)`);
  env.DB.db.exec(`INSERT INTO daily_dimension_sizes VALUES('2026-10-04','model',${DISTINCT_VALUES_PER_DAY})`);
  env.DB.db.exec(`INSERT INTO daily_dimensions VALUES('2026-10-04','model','["Example","Known"]',1)`);
  assert.equal((await handle(request({...ping(),model:'Forged 1'}),env,now)).status,200);
  assert.equal((await handle(request({...ping(),model:'Known'}),env,now)).status,200);
  const models=env.DB.rows("SELECT value,count FROM daily_dimensions WHERE dimension='model' ORDER BY value");
  assert.deepEqual(models,[{value:'["Example","Known"]',count:2},{value:'other',count:1}]);
  // Dimensions that still have room keep their real values.
  assert.equal(env.DB.rows("SELECT * FROM daily_dimensions WHERE dimension='version' AND value!='other'").length,1);
});

test('database failures fail closed and never reflect exception messages',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  env.DB.batch=async()=>{throw Error('private raw data');};
  const response=await handle(request(),env,now);
  assert.equal(response.status,503);assert.ok(!(await response.text()).includes('private'));
});


test('invalid retention configuration prevents ingestion rather than collecting without expiry',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  env.AGGREGATE_RETENTION_DAYS='0';
  assert.equal((await handle(request(),env,now)).status,503);
  assert.equal(env.DB.rows('SELECT * FROM daily_totals').length,0);
});
