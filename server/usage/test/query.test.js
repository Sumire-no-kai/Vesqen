import {test} from 'node:test';
import assert from 'node:assert/strict';
import {querySql} from '../scripts/query.js';
import {readFileSync} from 'node:fs';
import catalog from '../src/metric-catalog.json' with {type:'json'};

test('owner queries validate all interpolated inputs and hide expired report documents',()=>{
  assert.match(querySql('daily','2026-10-04'),/daily_dimensions/);
  assert.match(querySql('reports'),/expires_at > unixepoch/);
  assert.match(querySql('report','12345678-1234-1234-1234-123456789abc'),/expires_at > unixepoch/);
  for(const input of ["2026-10-04';DELETE FROM reports;--",'2026-02-31','invalid']) assert.throws(()=>querySql('daily',input));
  assert.throws(()=>querySql('report',"' OR 1=1 --"));
  assert.throws(()=>querySql('reports','unexpected'));
});

test('report metric schema cannot silently drift from Android catalog',()=>{
  const kotlin=readFileSync(new URL('../../../app/src/main/java/io/github/sumirenokai/vesqen/telemetry/TelemetryMetricCatalog.kt',import.meta.url),'utf8');
  const ids=Object.fromEntries([...kotlin.matchAll(/val (\w+) = id\("([^"]+)"\)/g)].map(match=>[match[1],match[2]]));
  const expected={};
  for(const match of kotlin.matchAll(/\b(text|safeText|integer|decimal|flag|usbInventory)\((\w+), TelemetrySection\.\w+(?:, TelemetryUnit\.(\w+))?/g)) {
    if(ids[match[2]]) expected[ids[match[2]]]={kind:match[1],...(match[3]?{unit:match[3]}:{})};
  }
  assert.ok(Object.keys(expected).length>100);
  assert.equal(Object.keys(expected).length,Object.keys(ids).length);
  assert.deepEqual(catalog,expected);
});
