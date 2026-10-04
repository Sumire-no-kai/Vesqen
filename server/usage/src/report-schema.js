import catalog from './metric-catalog.json' with {type: 'json'};
import {object, requireValid as check, checkFields, boolean, integer, label, nullable, oneOf, mac} from './validation.js';
const containers = ['flac','wave','wav','aiff','mpeg_audio','mp4','adts','ogg'];
const mimes = ['audio/mp4','audio/wav','audio/x-wav','audio/aiff','audio/flac','audio/raw','audio/alac','audio/mpeg','audio/mp4a-latm','audio/aac','audio/opus','audio/vorbis','audio/ac3','audio/eac3','audio/ac4','audio/true-hd'];
const codecs = ['FLAC','ALAC','PCM','MP3','AAC','Opus','Vorbis'];
const routeTypes = ['built_in_speaker','built_in_speaker_safe','dock','fm','ip','bus','remote_submix','built_in_earpiece','speaker','earpiece','wired_headset','wired_headphones','line_analog','line_digital','hdmi','hdmi_arc','hdmi_earc','usb_device','usb_accessory','usb_headset','bluetooth','bluetooth_a2dp','bluetooth_sco','ble_headset','ble_speaker','ble_broadcast','hearing_aid','other','unknown'];
const encodings = ['pcm-8','pcm-16','pcm-24','pcm-32','pcm-float'];
const platformEncoding = v => typeof v === 'string' && (['pcm_8','pcm_16','pcm_24','pcm_32','pcm_float','ac3','e_ac3','dts','dts_hd'].includes(v) || /^encoding_[0-9]{1,10}$/.test(v));
const unavailable = ['NO_ACTIVE_PLAYBACK','NOT_SAMPLED','NOT_EXPOSED_BY_PLATFORM','UNSUPPORTED_ANDROID_VERSION','UNSUPPORTED_DEVICE','PERMISSION_NOT_GRANTED','WARMING_UP','SOURCE_DID_NOT_REPORT','NOT_APPLICABLE','TEMPORARILY_UNAVAILABLE','UNKNOWN'];
const availability = oneOf(['AVAILABLE','STORAGE_UNAVAILABLE','QUEUE_OVERFLOW']);
const sources = ['library.metadata','media3.analytics','media3.decoder_counters','media3.player','media3.data_source','media3.current_media_data_source','media3.audio_track','android.media_codec','android.process','android.runtime','android.power','android.build','android.battery','android.audio_route','android.system_media_route','android.direct_playback_support','android.mixer_attributes','android.usb_public_api','vesqen.configuration','vesqen.output_coordinator','vesqen.external_output_verification'];
const calculations = ['decoder.path_from_public_runtime_facts','processing.compare_decoder_input_and_audio_track_rates','media3.position_from_last_event','rate.data_source_bytes_per_window','rate.current_media_bytes_per_window','rate.decoder_input_buffers_per_window','rate.decoder_output_buffers_per_window','audio_track.request_format_pcm_data_rate','rate.process_cpu_one_core','power.whole_device_current_times_voltage'];
const finite = v => typeof v === 'number' && Number.isFinite(v);
const count = maximum => v => integer(v) && v <= maximum;
const array = (v, maximum, predicate) => Array.isArray(v) && v.length <= maximum && v.every(predicate);
const pathlessLabel = v => label(v) && !/\.(?:flac|alac|wav|wave|aiff?|mp3|mp4|m4a|aac|opus|ogg|wma|mkv|txt|json|log|db|sqlite|jpg|png|pdf|zip|apk)(?:\b|$)/i.test(v);
const filename = v => typeof v === 'string' && v.length > 0 && v.length <= 255 && !/[\x00-\x1f\x7f-\x9f/\\:%]/.test(v) && !mac.test(v) && !['.','..'].includes(v);

function textReading(id, v) {
  if (typeof v !== 'string' || v.length > 300) return false;
  switch (id) {
    case 'source.container': return containers.includes(v);
    case 'source.codec_mime': case 'decoder.input_mime': return mimes.includes(v);
    case 'source.codec_label': return codecs.includes(v);
    case 'decoder.output_encoding': case 'playback.audio_track_encoding': return encodings.includes(v);
    case 'decoder.output_channel_config': case 'playback.audio_track_channel_mask': return /^0x[0-9a-fA-F]{1,8}$/.test(v);
    case 'route.selected_system_type': case 'route.anticipated_type': return routeTypes.includes(v);
    case 'route.connected_types': case 'route.bluetooth_connected_types': return v.split(',').every(x => routeTypes.includes(x.trim()));
    case 'route.request_format_direct_modes': return v.split(',').every(x => ['none','offload','offload_gapless','bitstream'].includes(x));
    case 'route.anticipated_preferred_mixer_profile': return v.split(',').every(x => ['default','bit_perfect_capability'].includes(x) || platformEncoding(x) || /^\d{1,7}hz$/.test(x) || /^mask_0x[0-9a-f]{1,8}$/.test(x));
    case 'route.output_declaration': return ['SYSTEM MIXED','DIRECT SUPPORTED','BIT-PERFECT AVAILABLE','BIT-PERFECT REQUESTED','BIT-PERFECT ACTIVE','BIT-PERFECT VERIFIED','BIT-PERFECT FAILED','SYSTEM_MIXED','DIRECT_SUPPORTED','BIT_PERFECT_AVAILABLE','BIT_PERFECT_REQUESTED','BIT_PERFECT_ACTIVE','BIT_PERFECT_VERIFIED','BIT_PERFECT_FAILED'].includes(v);
    case 'playback.state': return ['idle','buffering','ready','ended'].includes(v);
    default: return false;
  }
}
function inventory(v) {
  object(v, ['hostDevices','audioEndpoints']);
  check(array(v.hostDevices, 64, device => {
    checkFields(device, {ordinal:count(64), vendorId:count(65535), productId:count(65535), permissionGranted:boolean,
      interfaces: value => array(value, 64, item => { checkFields(item, {class:count(255),subclass:count(255),protocol:count(255)}); return true; })});
    return true;
  }));
  check(array(v.audioEndpoints, 64, endpoint => {
    checkFields(endpoint, {ordinal:count(64),type: value => nullable(value, oneOf(routeTypes)),
      sampleRatesHz:value => array(value,64,count(1536000)),channelCounts:value => array(value,64,count(64)),
      encodings:value => array(value,64,item => nullable(item, platformEncoding)),
      arbitrarySampleRate:boolean,arbitraryChannelCount:boolean,arbitraryEncoding:boolean});
    return true;
  }));
}
function metric(m) {
  object(m, ['id','confidence','observedAtEpochMs','observedAtElapsedRealtimeMs','source','sourceRedacted','reading'],
    ['unavailableReason','methodId','methodRedacted','calculationId','calculationRedacted','inputMetricIds','operands','window']);
  check(Object.hasOwn(catalog,m.id));
  check(integer(m.observedAtEpochMs) && integer(m.observedAtElapsedRealtimeMs));
  check(nullable(m.source, oneOf(sources)) && boolean(m.sourceRedacted));
  const extras = Object.keys(m).filter(k => !['id','confidence','observedAtEpochMs','observedAtElapsedRealtimeMs','source','sourceRedacted','reading'].includes(k));
  if (m.confidence === 'UNAVAILABLE') {
    check(extras.length === 1 && extras[0] === 'unavailableReason' && m.reading === null && unavailable.includes(m.unavailableReason));
    return;
  }
  check(['MEASURED','DERIVED','ESTIMATED'].includes(m.confidence));
  const definition = catalog[m.id];
  if (['text','safeText'].includes(definition.kind)) {
    if (Object.hasOwn(m.reading ?? {}, 'redactedReason')) checkFields(m.reading, {redactedReason:v => v === 'UNREVIEWED_OR_PRIVATE_TEXT'});
    else checkFields(m.reading, {value:v => textReading(m.id,v)});
  } else if (definition.kind === 'usbInventory') inventory(m.reading);
  else if (definition.kind === 'flag') checkFields(m.reading, {value:boolean});
  else checkFields(m.reading, {value:definition.kind === 'integer' ? v => Number.isSafeInteger(v) : finite, unit:v => v === definition.unit});
  if (m.confidence === 'MEASURED') { check(extras.length === 0); return; }
  check(array(m.inputMetricIds, 150, id => Object.hasOwn(catalog,id)));
  if (m.confidence === 'ESTIMATED') {
    check(extras.length === 3 && extras.every(k => ['methodId','methodRedacted','inputMetricIds'].includes(k)));
    check(nullable(m.methodId, oneOf(calculations)) && boolean(m.methodRedacted));
  } else {
    check(extras.length === 5 && extras.every(k => ['calculationId','calculationRedacted','inputMetricIds','operands','window'].includes(k)));
    check(nullable(m.calculationId, oneOf(calculations)) && boolean(m.calculationRedacted));
    check(array(m.operands, 32, item => {checkFields(item, {name:v => nullable(v, oneOf(['bytes.delta','window.seconds','cpu.delta_ms','window.elapsed_ms','counter.delta'])),ordinal:count(32),value:finite});return true;}));
    checkFields(m.window, {startedAtEpochMs:integer,endedAtEpochMs:integer,startedAtElapsedRealtimeMs:integer,endedAtElapsedRealtimeMs:integer});
    check(m.window.endedAtElapsedRealtimeMs > m.window.startedAtElapsedRealtimeMs && m.observedAtElapsedRealtimeMs >= m.window.endedAtElapsedRealtimeMs);
  }
}
function snapshot(s) {
  if (Object.hasOwn(s ?? {},'unavailableReason')) {checkFields(s,{unavailableReason:oneOf(unavailable)});return;}
  object(s, ['capturedAtEpochMs','capturedAtElapsedRealtimeMs','metrics']);
  check(integer(s.capturedAtEpochMs) && integer(s.capturedAtElapsedRealtimeMs));
  check(array(s.metrics,150,m => {metric(m);check(m.observedAtElapsedRealtimeMs <= s.capturedAtElapsedRealtimeMs);return true;}));
  const ids = new Set(s.metrics.map(m => m.id));
  check(ids.size === s.metrics.length);
  check(s.metrics.every(m => (m.inputMetricIds ?? []).every(id => ids.has(id))));
}
const failures = ['UNSUPPORTED_ANDROID_VERSION','USB_HOST_UNAVAILABLE','MODIFY_AUDIO_SETTINGS_DENIED','NO_USB_AUDIO_DEVICE','SOURCE_FORMAT_UNKNOWN','SOURCE_FORMAT_UNSUPPORTED','MIXER_QUERY_FAILED','NO_MATCHING_MIXER_ATTRIBUTE','MIXER_REQUEST_REJECTED','MIXER_READBACK_MISMATCH','MIXER_CLEAR_FAILED','AUDIO_TRACK_FORMAT_MISMATCH','PREFERRED_DEVICE_REJECTED','ROUTE_UNAVAILABLE','ROUTE_MISMATCH','DEVICE_DISCONNECTED','PROCESSING_NOT_NEUTRAL','SERVICE_STOPPED','PLATFORM_ERROR'];
export function validateReport(v) {
  object(v, ['schemaVersion','generatedAtEpochMs','basic'], ['audioCapabilities','chainEvidence','recentErrors','failedTrackFormats']);
  check(v.schemaVersion === 1 && integer(v.generatedAtEpochMs));
  checkFields(v.basic, {appVersion:x => nullable(x,pathlessLabel),versionCode:integer,buildType:x => nullable(x,pathlessLabel),
    manufacturer:x => nullable(x,pathlessLabel),model:x => nullable(x,pathlessLabel),androidVersion:x => nullable(x,pathlessLabel),
    androidApi:count(1000),romBuild:x => nullable(x,pathlessLabel),redactedValuesAreNull:x => x === true});
  for (const key of ['audioCapabilities','chainEvidence']) if (Object.hasOwn(v,key)) snapshot(v[key]);
  if (Object.hasOwn(v,'recentErrors')) {
    checkFields(v.recentErrors, {availability,exitHistory:oneOf(['AVAILABLE','UNSUPPORTED_ANDROID_VERSION','PLATFORM_UNAVAILABLE']),
      maxEvents:x => x === 100,maxAgeMs:x => x === 604800000,events:events => array(events,100,event => {
        checkFields(event, {kind:oneOf(['PLAYBACK','STRICT_OUTPUT','PROCESS_EXIT']),occurredAtEpochMs:integer,
          occurredAtElapsedRealtimeMs:x => nullable(x,integer),platformCode:x => nullable(x,integer),
          strictFailure:x => nullable(x,oneOf(failures)),strictOrigin:x => nullable(x,oneOf(['SERVICE_START','QUEUE_RESTORE','USER_PLAYBACK','USER_MODE_CHANGE','TRACK_TRANSITION','ROUTE_CHANGE','PROCESSING_CHANGE','SERVICE_STOP'])),
          confidence:x => x === 'MEASURED',source:oneOf(['media3.player','vesqen.output_coordinator','android.historical_process_exit'])});
        if (event.kind === 'STRICT_OUTPUT') check(event.strictFailure !== null && event.platformCode === null && event.source === 'vesqen.output_coordinator');
        else check(event.strictFailure === null && event.strictOrigin === null && event.platformCode !== null && event.source === (event.kind === 'PLAYBACK' ? 'media3.player' : 'android.historical_process_exit'));
        return true;
      })});
  }
  if (Object.hasOwn(v,'failedTrackFormats')) {
    object(v.failedTrackFormats,['availability','tracks']);check(availability(v.failedTrackFormats.availability));
    check(array(v.failedTrackFormats.tracks,100,track => {
      object(track,['occurredAtEpochMs','occurredAtElapsedRealtimeMs','confidence','source','container','codecMime','codecLabel','sampleRateHz','bitDepth','channelCount','privacyFiltered','missingFieldReason'],['fileName','fileNameUnavailableReason']);
      check(integer(track.occurredAtEpochMs) && nullable(track.occurredAtElapsedRealtimeMs,integer));
      check(track.confidence === 'MEASURED' && ['LIBRARY_METADATA','STRICT_OUTPUT_REQUEST'].includes(track.source));
      check(nullable(track.container,oneOf(containers)) && nullable(track.codecMime,oneOf(mimes)) && nullable(track.codecLabel,oneOf(codecs)));
      check(nullable(track.sampleRateHz,count(1536000)) && nullable(track.bitDepth,count(64)) && nullable(track.channelCount,count(64)));
      check(boolean(track.privacyFiltered) && ['SOURCE_DID_NOT_REPORT','SOURCE_DID_NOT_REPORT_OR_PRIVACY_FILTERED'].includes(track.missingFieldReason));
      if (Object.hasOwn(track,'fileName')) check(nullable(track.fileName,filename));
      if (Object.hasOwn(track,'fileNameUnavailableReason')) check(track.fileName === null && ['NOT_RECORDED_OR_PRIVACY_FILTERED','PRIVACY_FILTERED'].includes(track.fileNameUnavailableReason));
      return true;
    }));
  }
  return v;
}
