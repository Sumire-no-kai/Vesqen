import {test} from 'node:test';
import assert from 'node:assert/strict';
import {querySql} from '../scripts/query.js';

test('owner queries validate all interpolated inputs and hide expired report documents',()=>{
  assert.match(querySql('daily','2026-10-04'),/daily_dimensions/);
  assert.match(querySql('reports'),/expires_at > unixepoch/);
  assert.match(querySql('report','12345678-1234-1234-1234-123456789abc'),/expires_at > unixepoch/);
  for(const input of ["2026-10-04';DELETE FROM reports;--",'2026-02-31','invalid']) assert.throws(()=>querySql('daily',input));
  assert.throws(()=>querySql('report',"' OR 1=1 --"));
  assert.throws(()=>querySql('reports','unexpected'));
  assert.match(querySql('weekly','2026-09-28'),/BETWEEN '2026-09-28' AND '2026-10-04'/);
  assert.throws(()=>querySql('weekly','2026-09-29'));
  assert.match(querySql('monthly','2026-10'),/substr\(day,1,7\)='2026-10'/);
  assert.throws(()=>querySql('monthly',"2026-10' OR 1=1 --"));
  assert.match(querySql('report-counts'),/daily_report_counts/);
  assert.match(querySql('delete-report','12345678-1234-1234-1234-123456789abc'),/^DELETE FROM reports WHERE id='12345678/);
  assert.throws(()=>querySql('delete-report',"x' OR '1'='1"));
});
