import {test} from 'node:test';
import assert from 'node:assert/strict';
import {snapshotDownloads} from '../src/downloads.js';
import {environment} from './database.js';
import {querySql} from '../scripts/query.js';
const now=Date.parse('2026-10-08T03:00:00Z');
const releases=[
  {tag_name:'v1.0.0-beta.2',draft:false,assets:[
    {name:'Vesqen-1.0.0-beta.2.apk',download_count:12},
    {name:'SHA256SUMS',download_count:3},{name:'release-manifest.json',download_count:40}]},
  {tag_name:'v1.0.0-beta.3',draft:true,assets:[{name:'Vesqen-1.0.0-beta.3.apk',download_count:0}]},
  {tag_name:'v1.0.0-beta.1',draft:false,assets:[{name:'Vesqen-1.0.0-beta.1.apk',download_count:7}]},
  {tag_name:'not a tag',draft:false,assets:[{name:'Other.apk',download_count:1}]},
  {tag_name:'v0.9.0',draft:false,assets:[{name:'Vesqen-0.9.0.apk',download_count:-1}]},
];
function github(status=200,body=releases) {
  const requests=[];
  const fetcher=async (url,init)=>{requests.push({url,init});return new Response(JSON.stringify(body),{status});};
  return {requests,fetcher};
}

test('download counts are off until a repository is configured',async t=>{
  const env=environment();t.after(()=>env.DB.close());
  const {requests,fetcher}=github();
  await snapshotDownloads(env,now,fetcher);
  assert.equal(requests.length,0);
  await assert.rejects(snapshotDownloads({...env,DOWNLOAD_COUNTS_REPO:'owner/repo/../x'},now,fetcher));
  assert.equal(requests.length,0);
});

test('one snapshot per UTC day keeps only published APK counters',async t=>{
  const env={...environment(),DOWNLOAD_COUNTS_REPO:'Sumire-no-kai/Vesqen'};t.after(()=>env.DB.close());
  const {requests,fetcher}=github();
  await snapshotDownloads(env,now,fetcher);
  assert.deepEqual(env.DB.rows('SELECT * FROM daily_downloads ORDER BY release_tag'),[
    {day:'2026-10-08',release_tag:'v1.0.0-beta.1',asset:'Vesqen-1.0.0-beta.1.apk',downloads:7},
    {day:'2026-10-08',release_tag:'v1.0.0-beta.2',asset:'Vesqen-1.0.0-beta.2.apk',downloads:12},
  ]);
  // A public, unauthenticated read: no credentials and nothing about any user.
  assert.equal(requests[0].url,'https://api.github.com/repos/Sumire-no-kai/Vesqen/releases?per_page=100');
  assert.deepEqual(requests[0].init.headers,
    {Accept:'application/vnd.github+json','User-Agent':'vesqen-usage-worker','X-GitHub-Api-Version':'2022-11-28'});
  assert.equal(requests[0].init.body,undefined);
  await snapshotDownloads(env,now+3600000,fetcher);
  assert.equal(requests.length,1);
  await snapshotDownloads(env,now+86400000,fetcher);
  assert.equal(requests.length,2);
  assert.equal(env.DB.rows('SELECT * FROM daily_downloads').length,4);
});

test('a failed read stores nothing and the next hourly run tries again',async t=>{
  const env={...environment(),DOWNLOAD_COUNTS_REPO:'Sumire-no-kai/Vesqen'};t.after(()=>env.DB.close());
  for (const [status,body] of [[403,{message:'rate limited'}],[200,{not:'a list'}],[302,releases]]) {
    await assert.rejects(snapshotDownloads(env,now,github(status,body).fetcher));
    assert.equal(env.DB.rows('SELECT * FROM daily_downloads').length,0);
  }
  await snapshotDownloads(env,now,github().fetcher);
  assert.equal(env.DB.rows('SELECT * FROM daily_downloads').length,2);
});

test('the owner query shows the change since the previous snapshot',async t=>{
  const env={...environment(),DOWNLOAD_COUNTS_REPO:'Sumire-no-kai/Vesqen'};t.after(()=>env.DB.close());
  await snapshotDownloads(env,now,github().fetcher);
  const later=structuredClone(releases);later[0].assets[0].download_count=20;
  await snapshotDownloads(env,now+86400000,github(200,later).fetcher);
  const rows=env.DB.rows(querySql('downloads')).filter(row=>row.release_tag==='v1.0.0-beta.2');
  assert.deepEqual(rows.map(row=>[row.day,row.downloads,row.since_previous_snapshot]),
    [['2026-10-09',20,8],['2026-10-08',12,null]]);
});
