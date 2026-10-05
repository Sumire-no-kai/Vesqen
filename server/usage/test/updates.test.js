import {test} from 'node:test';
import assert from 'node:assert/strict';
import {updateManifests} from '../src/updates.js';
import {readJson} from '../src/validation.js';

test('existing static manifests are relayed without request metadata',async()=>{
  const calls=[];
  const result=await updateManifests('https://updates.example.invalid/updates/',async(url,options)=>{
    calls.push([url,options]);
    const channel=url.endsWith('/beta.json')?'beta':'stable';
    return Response.json({schemaVersion:1,channel,release:null});
  });
  assert.deepEqual(Object.keys(result),['stable','beta']);
  assert.deepEqual(calls.map(([url])=>url),['https://updates.example.invalid/updates/stable.json','https://updates.example.invalid/updates/beta.json']);
  // Workers fetch rejects redirect:'error'; 'manual' leaves a redirect as a non-200 that is dropped.
  assert.ok(calls.every(([,options])=>options.redirect==='manual'&&Object.keys(options.headers).length===1));
});
test('an upstream body Workers already decoded, later manifest fields and redirects are handled',async()=>{
  const manifest=channel=>({schemaVersion:1,channel,release:null,generatedBy:'release.py'});
  // workerd decodes gzip but keeps the Content-Encoding header on the response it hands back.
  const gzipHeader=await updateManifests('https://example.invalid/updates/',async url=>
    Response.json(manifest(url.endsWith('/beta.json')?'beta':'stable'),{headers:{'content-encoding':'gzip'}}));
  assert.deepEqual(Object.keys(gzipHeader),['stable','beta']);
  const redirected=await updateManifests('https://example.invalid/updates/',async()=>
    new Response(null,{status:302,headers:{location:'https://elsewhere.invalid/stable.json'}}));
  assert.deepEqual(redirected,{});
});
test('no configured origin or an invalid upstream never creates a new version source',async()=>{
  const fail=()=>{throw Error('must not fetch');};
  assert.deepEqual(await updateManifests('',fail),{});
  assert.deepEqual(await updateManifests('http://invalid/updates/',fail),{});
  assert.deepEqual(await updateManifests('https://example.invalid/updates/',async()=>Response.json({schemaVersion:1,channel:'stable',release:{}})),{});
  assert.deepEqual(await updateManifests('https://example.invalid/updates/',async()=>new Response('x'.repeat(60001),{headers:{'content-type':'application/json'}})),{});
});
test('a stalled request stream times out and is cancelled',async()=>{
  let cancelled=false;
  const stream=new ReadableStream({cancel(){cancelled=true;}});
  const input=new Request('https://example.invalid',{method:'POST',body:stream,duplex:'half',headers:{'content-type':'application/json'}});
  await assert.rejects(readJson(input,4096,10),error=>error.status===408);
  assert.equal(cancelled,true);
});
