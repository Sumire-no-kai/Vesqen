#!/usr/bin/env node
// Owner tool for activation codes (#120, docs/ACTIVATION_CODES.md). Runs on the owner's machine;
// the private key never goes to the Worker, the repository or CI.
//
//   node scripts/activation-codes.js keygen --key-id 1 --out <private.pem>
//   node scripts/activation-codes.js issue --key <private.pem> --key-id 1 --ledger <ledger.json> --count 500 --out <codes.csv>
//   node scripts/activation-codes.js verify --key-id 1 --public <SPKI base64> <code>
import {generateKeyPairSync, createPrivateKey, createPublicKey} from 'node:crypto';
import {existsSync, readFileSync, renameSync, writeFileSync} from 'node:fs';
import {parseArgs} from 'node:util';
import {signCode, verifyCode} from '../src/activation-code.js';

const [command, ...rest] = process.argv.slice(2);
const {values, positionals} = parseArgs({args:rest, allowPositionals:true, options:{
  'key-id':{type:'string'}, out:{type:'string'}, key:{type:'string'}, ledger:{type:'string'},
  count:{type:'string'}, public:{type:'string'}, init:{type:'boolean', default:false},
}});
const keyId = Number(values['key-id']);

function fail(message) {
  console.error(message);
  process.exit(1);
}

function spkiOf(keyObject) {
  return createPublicKey(keyObject).export({format:'der', type:'spki'}).toString('base64');
}

if (command === 'keygen') {
  if (!values.out) fail('--out is required');
  if (existsSync(values.out)) fail(`${values.out} exists; refusing to overwrite a key`);
  const {privateKey} = generateKeyPairSync('ec', {namedCurve:'prime256v1'});
  writeFileSync(values.out, privateKey.export({format:'pem', type:'pkcs8'}), {mode:0o600, flag:'wx'});
  console.log(`key id ${keyId}\npublic key (SPKI, base64) for the app:\n${spkiOf(privateKey)}`);
} else if (command === 'issue') {
  const count = Number(values.count);
  if (!values.key || !values.ledger || !values.out || !Number.isInteger(count) || count < 1 || count > 10000) {
    fail('issue needs --key, --key-id, --ledger, --out and --count between 1 and 10000');
  }
  if (existsSync(values.out)) fail(`${values.out} exists; refusing to overwrite issued codes`);
  // The ledger is the only record of which serials exist; without it serials could repeat.
  if (!existsSync(values.ledger) && !values.init) fail(`${values.ledger} is missing; pass --init only for a brand-new key`);
  const ledger = existsSync(values.ledger) ? JSON.parse(readFileSync(values.ledger, 'utf8')) : {keys:{}};
  const pem = readFileSync(values.key, 'utf8');
  const entry = ledger.keys[keyId] ?? {publicKey:spkiOf(createPrivateKey(pem)), lastSerial:0};
  if (entry.publicKey !== spkiOf(createPrivateKey(pem))) fail('the key does not match this key id in the ledger');
  const now = Date.now();
  const rows = ['serial,code'];
  for (let serial = entry.lastSerial + 1; serial <= entry.lastSerial + count; serial++) {
    rows.push(`${serial},${signCode(pem, {keyId, serial, issuedAtMs:now})}`);
  }
  writeFileSync(values.out, rows.join('\n') + '\n', {mode:0o600, flag:'wx'});
  ledger.keys[keyId] = {...entry, lastSerial:entry.lastSerial + count};
  writeFileSync(`${values.ledger}.tmp`, JSON.stringify(ledger, null, 2) + '\n', {mode:0o600});
  renameSync(`${values.ledger}.tmp`, values.ledger);
  console.log(`issued serials ${entry.lastSerial + 1}-${entry.lastSerial + count} for key ${keyId}`);
} else if (command === 'verify') {
  if (!values.public || positionals.length !== 1) fail('verify needs --key-id, --public and one code');
  console.log(JSON.stringify(verifyCode(positionals[0], {[keyId]:values.public})));
} else {
  fail('usage: activation-codes.js keygen|issue|verify (see the header of this file)');
}
