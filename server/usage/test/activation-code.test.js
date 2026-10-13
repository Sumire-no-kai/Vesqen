import {test} from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {generateKeyPairSync, createPublicKey} from 'node:crypto';
import {mkdtempSync, readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {decode, display, encode, signCode, verifyCode} from '../src/activation-code.js';
import {Database} from './database.js';

// Shared with the app's ActivationCodeVerifierTest: a throwaway key whose private half was deleted.
export const VECTOR = {
  keyId: 7,
  publicKey: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyDd+AYOVFuNa5vr80dCh/gblFQu3+LACGNdsXOv9B+xE3z9rihVy6gOG42pMv6+hcdyMaBauDrBqTTSi7DSQiA==',
  code: '043G2000000G278PHVTMZGE5NFP674D4NJNW1J79H4S7Q92ZPJCSHAGMZ0H0TJ94XFXH26FEX52MAGGK68GH80V8MSMB3M3EWPKZHGY4T1TF13KB1H84A',
};

const script = new URL('../scripts/activation-codes.js', import.meta.url).pathname;

function freshKey() {
  const {privateKey} = generateKeyPairSync('ec', {namedCurve:'prime256v1'});
  return {
    pem: privateKey.export({format:'pem', type:'pkcs8'}),
    spki: createPublicKey(privateKey).export({format:'der', type:'spki'}).toString('base64'),
  };
}

test('the shared vector verifies to its serial, key and product', () => {
  assert.deepEqual(verifyCode(VECTOR.code, {[VECTOR.keyId]:VECTOR.publicKey}), {keyId:7, product:1, serial:1, issuedDay:285});
});

test('a signed code verifies, reads back its fields and tolerates how people copy it', () => {
  const key = freshKey();
  const code = signCode(key.pem, {keyId:129, serial:4242, issuedAtMs:Date.UTC(2026, 9, 13)});
  assert.equal(code.length, 117);
  const fields = verifyCode(code, {129:key.spki});
  assert.deepEqual(fields, {keyId:129, product:1, serial:4242, issuedDay:285});
  // Lowercase, groups, line breaks and the letters Crockford reads as digits all decode the same.
  const copied = display(code).toLowerCase().replace(/0/g, 'o').replace(/1/g, 'l').replace(/-(?=.{20})/, '\n');
  assert.deepEqual(verifyCode(copied, {129:key.spki}), fields);
});

test('an altered code, an unknown key or another key never verifies', () => {
  const key = freshKey();
  const code = signCode(key.pem, {keyId:1, serial:1, issuedAtMs:Date.UTC(2026, 9, 13)});
  const altered = code.slice(0, 20) + (code[20] === 'A' ? 'B' : 'A') + code.slice(21);
  assert.throws(() => verifyCode(altered, {1:key.spki}));
  assert.throws(() => verifyCode(code, {2:key.spki}), /key_unknown/);
  assert.throws(() => verifyCode(code, {1:freshKey().spki}), /signature_mismatch/);
  assert.throws(() => verifyCode(code.slice(1), {1:key.spki}), /length_invalid/);
  assert.throws(() => verifyCode(code.slice(0, -1) + 'U', {1:key.spki}), /character_invalid/);
});

test('base32 round-trips every byte value and rejects nonzero padding', () => {
  const bytes = Uint8Array.from({length:73}, (_, i) => (i * 37 + 11) & 0xff);
  assert.deepEqual(decode(encode(bytes)), bytes);
  const text = encode(new Uint8Array(73));
  assert.throws(() => decode(text.slice(0, -1) + '1'), /padding_invalid/);
});

test('the issue command never repeats a serial and keeps the ledger as the record', () => {
  const dir = mkdtempSync(join(tmpdir(), 'vesqen-codes-'));
  const pem = join(dir, 'key.pem');
  execFileSync(process.execPath, [script, 'keygen', '--key-id', '1', '--out', pem]);
  const ledger = join(dir, 'ledger.json');
  const run = (out, ...extra) => execFileSync(process.execPath,
    [script, 'issue', '--key', pem, '--key-id', '1', '--ledger', ledger, '--count', '3', '--out', join(dir, out), ...extra]);
  // Without a ledger the serials could start over, so a missing one needs an explicit --init.
  assert.throws(() => run('a.csv'));
  run('a.csv', '--init');
  run('b.csv');
  const serials = ['a.csv', 'b.csv'].flatMap(name =>
    readFileSync(join(dir, name), 'utf8').trim().split('\n').slice(1).map(row => Number(row.split(',')[0])));
  assert.deepEqual(serials, [1, 2, 3, 4, 5, 6]);
  assert.equal(JSON.parse(readFileSync(ledger, 'utf8')).keys['1'].lastSerial, 6);
  assert.throws(() => run('b.csv'), 'issued codes are never overwritten');
  assert.throws(() => execFileSync(process.execPath, [script, 'keygen', '--key-id', '1', '--out', pem]), 'a key is never overwritten');
  // A different key cannot continue another key's ledger.
  const other = join(dir, 'other.pem');
  execFileSync(process.execPath, [script, 'keygen', '--key-id', '1', '--out', other]);
  assert.throws(() => execFileSync(process.execPath,
    [script, 'issue', '--key', other, '--key-id', '1', '--ledger', ledger, '--count', '1', '--out', join(dir, 'c.csv')]));
  // The pool upload inserts each issued code once and leaves existing serials alone.
  execFileSync(process.execPath, [script, 'pool-sql', '--key-id', '1', '--codes', join(dir, 'a.csv'), '--out', join(dir, 'pool.sql')]);
  const sql = readFileSync(join(dir, 'pool.sql'), 'utf8').trim().split('\n');
  assert.equal(sql.length, 3);
  assert.match(sql[0], /^INSERT OR IGNORE INTO activation_codes\(key_id, serial, code\) VALUES\(1, 1, '[0-9A-Z]{117}'\);$/);
  const db = new Database();
  for (const line of [...sql, ...sql]) db.db.exec(line);
  assert.deepEqual(db.rows('SELECT serial FROM activation_codes ORDER BY serial').map(row => row.serial), [1, 2, 3]);
  db.close();
});
