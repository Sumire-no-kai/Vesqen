import {createPrivateKey, createPublicKey, sign, verify} from 'node:crypto';

/**
 * Vesqen activation codes (#120, docs/ACTIVATION_CODES.md). The app verifies the same format in
 * ActivationCodeVerifier.kt; the shared test vector in test/activation-code.test.js keeps them equal.
 *
 * A code is a 9-byte payload and a 64-byte ECDSA P-256 signature (r || s), written in Crockford
 * base32. The serial makes each code unique; the owner's offline ledger hands serials out in order.
 */
export const FORMAT_VERSION = 1;
export const PRODUCT_PRO = 1;
export const TEST_KEY_ID = 0x81;
const DOMAIN = new TextEncoder().encode('vesqen.activation.v1\n');
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const PAYLOAD_BYTES = 9;
const SIGNATURE_BYTES = 64;
const CODE_BYTES = PAYLOAD_BYTES + SIGNATURE_BYTES;
const EPOCH_DAY = Date.UTC(2026, 0, 1) / 86400000;

export function payload({keyId, serial, issuedAtMs, product = PRODUCT_PRO}) {
  if (!Number.isInteger(keyId) || keyId < 1 || keyId > 255) throw new Error('key_id_invalid');
  if (!Number.isInteger(serial) || serial < 1 || serial > 0xffffffff) throw new Error('serial_invalid');
  const day = Math.floor(issuedAtMs / 86400000) - EPOCH_DAY;
  if (!Number.isInteger(day) || day < 0 || day > 0xffff) throw new Error('issue_day_invalid');
  const bytes = new Uint8Array(PAYLOAD_BYTES);
  const view = new DataView(bytes.buffer);
  view.setUint8(0, FORMAT_VERSION);
  view.setUint8(1, keyId);
  view.setUint8(2, product);
  view.setUint32(3, serial);
  view.setUint16(7, day);
  return bytes;
}

export function signCode(privateKeyPem, fields) {
  const body = payload(fields);
  const signature = sign('sha256', concat(DOMAIN, body), {key:createPrivateKey(privateKeyPem), dsaEncoding:'ieee-p1363'});
  if (signature.length !== SIGNATURE_BYTES) throw new Error('signature_length');
  return encode(concat(body, signature));
}

/** Returns the decoded fields when [code] is genuine for one of [publicKeys] ({keyId: SPKI base64}). */
export function verifyCode(code, publicKeys) {
  const bytes = decode(code);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getUint8(0) !== FORMAT_VERSION) throw new Error('version_unsupported');
  const keyId = view.getUint8(1);
  const spki = publicKeys[keyId];
  if (!spki) throw new Error('key_unknown');
  const key = createPublicKey({key:Buffer.from(spki, 'base64'), format:'der', type:'spki'});
  const body = bytes.subarray(0, PAYLOAD_BYTES);
  const genuine = verify('sha256', concat(DOMAIN, body), {key, dsaEncoding:'ieee-p1363'}, bytes.subarray(PAYLOAD_BYTES));
  if (!genuine) throw new Error('signature_mismatch');
  return {keyId, product:view.getUint8(2), serial:view.getUint32(3), issuedDay:view.getUint16(7)};
}

/** Groups of five for reading aloud or typing; parsing ignores the separators. */
export function display(code) {
  return code.match(/.{1,5}/g).join('-');
}

export function encode(bytes) {
  let bits = 0, value = 0, out = '';
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
    value &= (1 << bits) - 1;
  }
  if (bits > 0) out += ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

export function decode(text) {
  const clean = String(text).toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  if (clean.length !== Math.ceil(CODE_BYTES * 8 / 5)) throw new Error('length_invalid');
  const bytes = new Uint8Array(CODE_BYTES);
  let bits = 0, value = 0, index = 0;
  for (const char of clean) {
    const digit = ALPHABET.indexOf(char);
    if (digit < 0) throw new Error('character_invalid');
    value = (value << 5) | digit;
    bits += 5;
    if (bits >= 8) {
      bytes[index++] = (value >>> (bits - 8)) & 0xff;
      bits -= 8;
    }
    value &= (1 << bits) - 1;
  }
  // The final character carries padding bits; a nonzero one means the text was altered.
  if (value !== 0) throw new Error('padding_invalid');
  return bytes;
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}
