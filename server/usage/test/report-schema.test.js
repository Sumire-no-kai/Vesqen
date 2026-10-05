import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateReport} from '../src/report-schema.js';
import {report} from './fixtures.js';
import vocabulary from '../../../contracts/device-report/vocabulary.json' with {type:'json'};
import sample from '../../../contracts/device-report/sample-report.json' with {type:'json'};
const measured = (id,reading) => ({id,confidence:'MEASURED',observedAtEpochMs:100,observedAtElapsedRealtimeMs:80,source:'media3.player',sourceRedacted:false,reading});
function fullReport() {
  const value=report();
  const input=measured('process.data_source_bytes_transferred_since_start',{value:4096,unit:'BYTES'});
  value.audioCapabilities={unavailableReason:'TEMPORARILY_UNAVAILABLE'};
  value.chainEvidence={capturedAtEpochMs:200,capturedAtElapsedRealtimeMs:200,metrics:[
    input,measured('route.selected_system_type',{value:'usb_device'}),
    measured('route.selected_system_name',{redactedReason:'UNREVIEWED_OR_PRIVATE_TEXT'}),
    {...measured('process.data_source_read_throughput',{value:16384,unit:'BITS_PER_SECOND'}),confidence:'DERIVED',
      calculationId:'rate.data_source_bytes_per_window',calculationRedacted:false,inputMetricIds:[input.id],
      operands:[{name:'bytes.delta',ordinal:0,value:4096},{name:'window.seconds',ordinal:1,value:2}],
      window:{startedAtEpochMs:50,endedAtEpochMs:100,startedAtElapsedRealtimeMs:50,endedAtElapsedRealtimeMs:80}},
    {...measured('decoder.output_encoding',null),confidence:'UNAVAILABLE',unavailableReason:'WARMING_UP'},
  ]};
  value.recentErrors={availability:'AVAILABLE',exitHistory:'AVAILABLE',maxEvents:100,maxAgeMs:604800000,
    events:[{kind:'PLAYBACK',occurredAtEpochMs:100,occurredAtElapsedRealtimeMs:80,platformCode:2000,strictFailure:null,strictOrigin:null,confidence:'MEASURED',source:'media3.player'}]};
  value.failedTrackFormats={availability:'AVAILABLE',tracks:[{occurredAtEpochMs:100,occurredAtElapsedRealtimeMs:80,confidence:'ESTIMATED',source:'LIBRARY_METADATA',container:'flac',codecMime:'audio/flac',codecLabel:'FLAC',sampleRateHz:48000,bitDepth:24,channelCount:2,privacyFiltered:false,missingFieldReason:'SOURCE_DID_NOT_REPORT'}]};
  return value;
}

test('#69 report contract accepts every group combination and optional basename',()=>{
  for(let mask=0;mask<32;mask++) {
    const value=fullReport();
    ['audioCapabilities','chainEvidence','recentErrors','failedTrackFormats'].forEach((key,index)=>{if(!(mask&(1<<index))) delete value[key];});
    if((mask&16)&&value.failedTrackFormats) value.failedTrackFormats.tracks[0].fileName='Track.flac';
    assert.equal(validateReport(value),value);
  }
});

test('unknown fields paths raw errors bluetooth names MAC and missing dependencies are rejected',()=>{
  const mutations=[
    v=>{v.library=['Track'];}, v=>{v.basic.path='/private/music';},v=>{v.basic.model='12:34:56:78:9A:BC';},
    v=>{v.recentErrors.events[0].message='error /private/music.flac';},
    v=>{v.chainEvidence.metrics[2].reading={value:'Secret Headphones'};},
    v=>{v.chainEvidence.metrics[0].source='private-track.flac';},
    v=>{v.chainEvidence.metrics[3].inputMetricIds=['source.sample_rate'];},
    v=>{v.chainEvidence.metrics[3].window.endedAtElapsedRealtimeMs=300;},
    v=>{v.chainEvidence.metrics[0].reading.unit='HERTZ';},
    v=>{v.failedTrackFormats.tracks[0].fileName='/storage/Track.flac';},
    v=>{v.failedTrackFormats.tracks[0].fileName='C:\\Music\\Track.flac';},
    v=>{v.failedTrackFormats.tracks[0].fileName='%2fTrack.flac';},
    v=>{v.failedTrackFormats.tracks[0].codecMime='error-path';},
  ];
  for(const mutation of mutations) {const value=fullReport();mutation(value);assert.throws(()=>validateReport(value));}
});

test('USB numeric capabilities retained without endpoint identity fields',()=>{
  const value=report();
  value.audioCapabilities={capturedAtEpochMs:100,capturedAtElapsedRealtimeMs:100,metrics:[measured('usb.device_inventory',{
    hostDevices:[{ordinal:1,vendorId:1,productId:2,permissionGranted:true,interfaces:[{class:1,subclass:2,protocol:32}]}],
    audioEndpoints:[{ordinal:1,type:'usb_device',sampleRatesHz:[96000],channelCounts:[2],encodings:['pcm_24'],arbitrarySampleRate:false,arbitraryChannelCount:false,arbitraryEncoding:false}],
  })]};
  validateReport(value);
  value.audioCapabilities.metrics[0].reading.hostDevices[0].productName='Private DAC';
  assert.throws(()=>validateReport(value));
});

test('the app-generated sample validates unchanged',()=>{
  assert.equal(validateReport(sample),sample);
});

test('all contract text values validate and values outside each allowlist are rejected',()=>{
  const cases={
    containers:['source.container'], mimes:['source.codec_mime','decoder.input_mime'], codecLabels:['source.codec_label'],
    encodings:['decoder.output_encoding','decoder.input_pcm_encoding','playback.audio_track_encoding'],
    routeTypes:['route.selected_system_type','route.anticipated_type','route.connected_types','route.bluetooth_connected_types'],
    outputDeclarations:['route.output_declaration'], directModes:['route.request_format_direct_modes'],
    mixerProfileTokens:['route.anticipated_preferred_mixer_profile'], playbackStates:['playback.state'],
  };
  const withMetric=m=>({...report(),chainEvidence:{capturedAtEpochMs:100,capturedAtElapsedRealtimeMs:100,metrics:[m]}});
  for(const [list,ids] of Object.entries(cases)) for(const id of ids) {
    for(const value of vocabulary.text[list]) {
      validateReport(withMetric(measured(id,{value})));
      assert.throws(()=>validateReport(withMetric(measured(id,{value:value+'!unreviewed'}))),`${id}: ${value}`);
    }
  }
  for(const id of cases.encodings) {
    validateReport(withMetric(measured(id,{value:'encoding-1234'})));
    for(const value of ['encoding--1','encoding-12345678901','prefix-encoding-1','encoding-1\n'])
      assert.throws(()=>validateReport(withMetric(measured(id,{value}))),value);
  }
  for(const value of vocabulary.text.platformEncodings.concat('encoding_1234')) {
    validateReport(withMetric(measured('route.anticipated_preferred_mixer_profile',{value})));
    assert.throws(()=>validateReport(withMetric(measured('route.anticipated_preferred_mixer_profile',{value:value+'!unreviewed'}))));
  }
});

test('metric value kinds and units come from the shared catalog',()=>{
  for(const definition of vocabulary.metrics) {
    const readings={TEXT:{redactedReason:'UNREVIEWED_OR_PRIVATE_TEXT'},FLAG:{value:true},
      INTEGER:{value:1,unit:definition.unit},DECIMAL:{value:0.5,unit:definition.unit},
      USB_INVENTORY:{hostDevices:[],audioEndpoints:[]}};
    assert.ok(Object.hasOwn(readings,definition.valueKind),definition.id);
    const value={...report(),chainEvidence:{capturedAtEpochMs:100,capturedAtElapsedRealtimeMs:100,
      metrics:[measured(definition.id,readings[definition.valueKind])]}};
    validateReport(value);
    const changed=structuredClone(value);
    changed.chainEvidence.metrics[0].reading={value:null,unit:'INVALID'};
    assert.throws(()=>validateReport(changed),definition.id);
    if(definition.unit!==null) {
      for(const unit of vocabulary.enums.telemetryUnit.filter(unit=>unit!==definition.unit)) {
        const incorrect=structuredClone(value);
        incorrect.chainEvidence.metrics[0].reading.unit=unit;
        assert.throws(()=>validateReport(incorrect),`${definition.id}: ${unit}`);
      }
    }
  }
});

test('mutated sample enums provenance text and confidence are rejected',()=>{
  function leaves(value,path=[]) {
    if(value && typeof value==='object') return Object.entries(value).flatMap(([key,item])=>leaves(item,[...path,key]));
    return [[path,value]];
  }
  const controlled=new Set(['id','confidence','source','unavailableReason','methodId','calculationId','unit',
    'redactedReason','availability','exitHistory','kind','strictFailure','strictOrigin','missingFieldReason','fileNameUnavailableReason',
    'status','sourceCompression','sourceCompressionMetricId','route','condition','reason','metricId']);
  for(const [path,original] of leaves(sample)) {
    const key=path.at(-1);
    const textReading=key==='value' && path.at(-2)==='reading' && typeof original==='string';
    const operand=key==='name' && path.includes('operands');
    const formatText=['container','codecMime','codecLabel'].includes(key);
    const dependency=path.includes('inputMetricIds') || path.includes('metricIds') || path.includes('routeMetricIds');
    if(typeof original!=='string' || !(controlled.has(key)||textReading||operand||formatText||dependency)) continue;
    const changed=structuredClone(sample);
    const parent=path.slice(0,-1).reduce((value,key)=>value[key],changed);
    parent[key]=original+'!unreviewed';
    assert.throws(()=>validateReport(changed),path.join('.'));
    if(key==='confidence') for(const confidence of vocabulary.enums.telemetryConfidence.filter(c=>c!==original)) {
      parent[key]=confidence;
      assert.throws(()=>validateReport(changed),`${path.join('.')}: ${confidence}`);
    }
  }
});

test('error availability format provenance and retention follow the contract',()=>{
  for(const availability of vocabulary.enums.errorHistoryAvailability) {
    const value=structuredClone(sample);
    value.recentErrors.availability=availability;
    value.failedTrackFormats.availability=availability;
    validateReport(value);
  }
  for(const exitHistory of vocabulary.enums.exitHistoryAvailability) {
    const value=structuredClone(sample);value.recentErrors.exitHistory=exitHistory;validateReport(value);
  }
  const strict=sample.recentErrors.events.findIndex(event=>event.kind==='STRICT_OUTPUT');
  for(const [field,values] of Object.entries({strictFailure:vocabulary.enums.usbOutputFailure,strictOrigin:vocabulary.enums.usbOutputFailureOrigin})) {
    for(const item of values) {
      const value=structuredClone(sample);value.recentErrors.events[strict][field]=item;validateReport(value);
      value.recentErrors.events[strict][field]=item+'!unreviewed';assert.throws(()=>validateReport(value));
    }
  }
  for(const [key,expected] of Object.entries(vocabulary.limits)) {
    const value=structuredClone(sample);value.recentErrors[key]=expected+1;assert.throws(()=>validateReport(value));
  }
  const tooMany=structuredClone(sample);
  tooMany.recentErrors.events=Array.from({length:vocabulary.limits.maxEvents+1},()=>sample.recentErrors.events[0]);
  assert.throws(()=>validateReport(tooMany));
  const tooManyFormats=structuredClone(sample);
  tooManyFormats.failedTrackFormats.tracks=Array.from({length:vocabulary.limits.maxEvents+1},()=>sample.failedTrackFormats.tracks[0]);
  assert.throws(()=>validateReport(tooManyFormats));
});


test('app segment is optional for old reports but requires chain evidence',()=>{
  const old=structuredClone(sample);delete old.appSegment;validateReport(old);
  const noChain=structuredClone(sample);delete noChain.chainEvidence;
  assert.throws(()=>validateReport(noChain));
  const capabilitiesOnly=structuredClone(sample);
  delete capabilitiesOnly.chainEvidence;delete capabilitiesOnly.appSegment;validateReport(capabilitiesOnly);
  assert.equal(sample.appSegment.route,'BLUETOOTH');
  assert.ok(sample.appSegment.checks.some(c=>c.status==='UNKNOWN' && c.reason!==null));
});

test('app segment rejects raw text missing conditions broken references and inconsistent states',()=>{
  const mutations=[
    a=>{a.message='/private/music.flac';},a=>{a.checks[0].value='Secret Headphones';},
    a=>{a.checks.pop();},a=>{a.checks[0]=a.checks[1];},
    a=>{a.checks[0].metricIds=['processing.speed'];}, // Missing from the sample snapshot.
    a=>{a.checks[0].issues=[{metricId:'route.selected_system_type',reason:'MISSING'}];},
    a=>{a.checks[0].issues=[{metricId:'processing.speed',reason:'UNAVAILABLE'}];},
    a=>{a.checks[0].reason=null;a.checks[0].issues=[];},
    a=>{a.status='UNCHANGED';},a=>{a.bluetooth=null;},
    a=>{a.route='OTHER';},a=>{a.bluetooth.codec='Secret Headphones';},
    a=>{a.routeMetricIds=[];},a=>{a.routeIssues=[{metricId:'route.selected_system_type',reason:'EXPIRED'}];},
  ];
  for(const mutation of mutations) {
    const value=structuredClone(sample);mutation(value.appSegment);
    assert.throws(()=>validateReport(value));
  }
});

test('unavailable snapshot and non-Bluetooth route preserve explicit segment states',()=>{
  const value=structuredClone(sample);
  value.chainEvidence={unavailableReason:'TEMPORARILY_UNAVAILABLE'};
  Object.assign(value.appSegment,{status:'UNKNOWN',sourceCompression:'UNKNOWN',route:'UNKNOWN',bluetooth:null,
    routeMetricIds:[],routeIssues:[{metricId:'route.selected_system_type',reason:'MISSING'}],
    checks:vocabulary.enums.appSegmentCondition.map(condition=>({condition,status:'UNKNOWN',reason:'MISSING',metricIds:[],issues:[]}))});
  validateReport(value);
  value.appSegment.checks.at(-1).status='UNCHANGED';
  value.appSegment.checks.at(-1).reason=null;
  assert.throws(()=>validateReport(value));
  for(const route of ['phone_speaker','wired_or_usb']) {
    const other=structuredClone(sample);
    other.chainEvidence.metrics.find(m=>m.id==='route.selected_system_type').reading.value=route;
    other.appSegment.route='OTHER';other.appSegment.bluetooth=null;
    validateReport(other);
  }
});
