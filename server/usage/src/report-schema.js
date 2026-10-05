import vocabulary from '../../../contracts/device-report/vocabulary.json' with {type: 'json'};
import {object, requireValid as check, checkFields, boolean, integer, label, nullable, oneOf, mac} from './validation.js';
const {enums, text, limits} = vocabulary;
const {containers, mimes, codecLabels: codecs, routeTypes, sources, calculations} = text;
const catalog = Object.fromEntries(vocabulary.metrics.map(metric => [metric.id, metric]));
const matchesPattern = pattern => {
  const regex = new RegExp(`^(?:${pattern})$`);
  return value => typeof value === 'string' && regex.exec(value)?.[0] === value;
};
const encodingPattern = matchesPattern(text.encodingPattern);
const platformEncodingPattern = matchesPattern(text.platformEncodingPattern);
const encoding = value => text.encodings.includes(value) || encodingPattern(value);
const platformEncoding = value => text.platformEncodings.includes(value) || platformEncodingPattern(value);
const unavailable = enums.telemetryUnavailableReason;
const availability = oneOf(enums.errorHistoryAvailability);
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
    case 'decoder.output_encoding': case 'decoder.input_pcm_encoding': case 'playback.audio_track_encoding': return encoding(v);
    case 'decoder.output_channel_config': case 'playback.audio_track_channel_mask': return /^0x[0-9a-fA-F]{1,8}$/.test(v);
    case 'route.selected_system_type': case 'route.anticipated_type': return routeTypes.includes(v);
    case 'route.connected_types': case 'route.bluetooth_connected_types': return v.split(',').every(x => routeTypes.includes(x.trim()));
    case 'route.request_format_direct_modes': return v.split(',').every(x => text.directModes.includes(x));
    case 'route.anticipated_preferred_mixer_profile': return v.split(',').every(x => text.mixerProfileTokens.includes(x) || platformEncoding(x) || /^\d{1,7}hz$/.test(x) || /^mask_0x[0-9a-f]{1,8}$/.test(x));
    case 'route.output_declaration': return text.outputDeclarations.includes(v);
    case 'playback.state': return text.playbackStates.includes(v);
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
  check(enums.telemetryConfidence.includes(m.confidence));
  if (m.confidence === 'UNAVAILABLE') {
    check(extras.length === 1 && extras[0] === 'unavailableReason' && m.reading === null && unavailable.includes(m.unavailableReason));
    return;
  }
  const definition = catalog[m.id];
  if (definition.valueKind === 'TEXT') {
    if (Object.hasOwn(m.reading ?? {}, 'redactedReason')) checkFields(m.reading, {redactedReason:v => v === 'UNREVIEWED_OR_PRIVATE_TEXT'});
    else checkFields(m.reading, {value:v => textReading(m.id,v)});
  } else if (definition.valueKind === 'USB_INVENTORY') inventory(m.reading);
  else if (definition.valueKind === 'FLAG') checkFields(m.reading, {value:boolean});
  else {
    check(['INTEGER','DECIMAL'].includes(definition.valueKind));
    checkFields(m.reading, {value:definition.valueKind === 'INTEGER' ? v => Number.isSafeInteger(v) : finite, unit:v => enums.telemetryUnit.includes(v) && v === definition.unit});
  }
  if (m.confidence === 'MEASURED') { check(extras.length === 0); return; }
  check(array(m.inputMetricIds, vocabulary.metrics.length, id => Object.hasOwn(catalog,id)));
  if (m.confidence === 'ESTIMATED') {
    check(extras.length === 3 && extras.every(k => ['methodId','methodRedacted','inputMetricIds'].includes(k)));
    check(nullable(m.methodId, oneOf(calculations)) && boolean(m.methodRedacted));
  } else {
    check(extras.length === 5 && extras.every(k => ['calculationId','calculationRedacted','inputMetricIds','operands','window'].includes(k)));
    check(nullable(m.calculationId, oneOf(calculations)) && boolean(m.calculationRedacted));
    check(array(m.operands, 32, item => {checkFields(item, {name:v => nullable(v, oneOf(text.operandNames)),ordinal:count(32),value:finite});return true;}));
    checkFields(m.window, {startedAtEpochMs:integer,endedAtEpochMs:integer,startedAtElapsedRealtimeMs:integer,endedAtElapsedRealtimeMs:integer});
    check(m.window.endedAtElapsedRealtimeMs > m.window.startedAtElapsedRealtimeMs && m.observedAtElapsedRealtimeMs >= m.window.endedAtElapsedRealtimeMs);
  }
}
function snapshot(s) {
  if (Object.hasOwn(s ?? {},'unavailableReason')) {checkFields(s,{unavailableReason:oneOf(unavailable)});return;}
  object(s, ['capturedAtEpochMs','capturedAtElapsedRealtimeMs','metrics']);
  check(integer(s.capturedAtEpochMs) && integer(s.capturedAtElapsedRealtimeMs));
  check(array(s.metrics,vocabulary.metrics.length,m => {metric(m);check(m.observedAtElapsedRealtimeMs <= s.capturedAtElapsedRealtimeMs);return true;}));
  const ids = new Set(s.metrics.map(m => m.id));
  check(ids.size === s.metrics.length);
  check(s.metrics.every(m => (m.inputMetricIds ?? []).every(id => ids.has(id))));
}
export function validateReport(v) {
  object(v, ['schemaVersion','generatedAtEpochMs','basic'], ['audioCapabilities','chainEvidence','recentErrors','failedTrackFormats']);
  check(v.schemaVersion === vocabulary.schemaVersion && integer(v.generatedAtEpochMs));
  checkFields(v.basic, {appVersion:x => nullable(x,pathlessLabel),versionCode:integer,buildType:x => nullable(x,pathlessLabel),
    manufacturer:x => nullable(x,pathlessLabel),model:x => nullable(x,pathlessLabel),androidVersion:x => nullable(x,pathlessLabel),
    androidApi:count(1000),romBuild:x => nullable(x,pathlessLabel),redactedValuesAreNull:x => x === true});
  for (const key of ['audioCapabilities','chainEvidence']) if (Object.hasOwn(v,key)) snapshot(v[key]);
  if (Object.hasOwn(v,'recentErrors')) {
    checkFields(v.recentErrors, {availability,exitHistory:oneOf(enums.exitHistoryAvailability),
      maxEvents:x => x === limits.maxEvents,maxAgeMs:x => x === limits.maxAgeMs,events:events => array(events,limits.maxEvents,event => {
        checkFields(event, {kind:oneOf(enums.reportErrorKind),occurredAtEpochMs:integer,
          occurredAtElapsedRealtimeMs:x => nullable(x,integer),platformCode:x => nullable(x,integer),
          strictFailure:x => nullable(x,oneOf(enums.usbOutputFailure)),strictOrigin:x => nullable(x,oneOf(enums.usbOutputFailureOrigin)),
          confidence:x => enums.telemetryConfidence.includes(x) && x === 'MEASURED',source:oneOf(['media3.player','vesqen.output_coordinator','android.historical_process_exit'])});
        if (event.kind === 'STRICT_OUTPUT') check(event.strictFailure !== null && event.platformCode === null && event.source === 'vesqen.output_coordinator');
        else check(event.strictFailure === null && event.strictOrigin === null && event.platformCode !== null && event.source === (event.kind === 'PLAYBACK' ? 'media3.player' : 'android.historical_process_exit'));
        return true;
      })});
  }
  if (Object.hasOwn(v,'failedTrackFormats')) {
    object(v.failedTrackFormats,['availability','tracks']);check(availability(v.failedTrackFormats.availability));
    check(array(v.failedTrackFormats.tracks,limits.maxEvents,track => {
      object(track,['occurredAtEpochMs','occurredAtElapsedRealtimeMs','confidence','source','container','codecMime','codecLabel','sampleRateHz','bitDepth','channelCount','privacyFiltered','missingFieldReason'],['fileName','fileNameUnavailableReason']);
      check(integer(track.occurredAtEpochMs) && nullable(track.occurredAtElapsedRealtimeMs,integer));
      check(enums.errorFormatSource.includes(track.source) && enums.telemetryConfidence.includes(track.confidence));
      // A cached library fact must never become measured output evidence.
      check((track.source === 'LIBRARY_METADATA' && track.confidence === 'ESTIMATED') ||
        (track.source === 'STRICT_OUTPUT_REQUEST' && track.confidence === 'MEASURED'));
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
