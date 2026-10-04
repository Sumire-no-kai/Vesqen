import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateReport} from '../src/report-schema.js';
import {report} from './fixtures.js';
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
  value.failedTrackFormats={availability:'AVAILABLE',tracks:[{occurredAtEpochMs:100,occurredAtElapsedRealtimeMs:80,confidence:'MEASURED',source:'LIBRARY_METADATA',container:'flac',codecMime:'audio/flac',codecLabel:'FLAC',sampleRateHz:48000,bitDepth:24,channelCount:2,privacyFiltered:false,missingFieldReason:'SOURCE_DID_NOT_REPORT'}]};
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
