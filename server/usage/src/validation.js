export class InvalidRequest extends Error {
  constructor(status = 400) { super('invalid_request'); this.status = status; }
}
export function requireValid(condition) { if (!condition) throw new InvalidRequest(); }
export function object(value, required, optional = []) {
  requireValid(value !== null && typeof value === 'object' && !Array.isArray(value));
  requireValid(required.every(key => Object.hasOwn(value, key)));
  requireValid(Object.keys(value).every(key => required.includes(key) || optional.includes(key)));
}
export const boolean = value => typeof value === 'boolean';
export const integer = value => Number.isSafeInteger(value) && value >= 0;
export const mac = /(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}/i;
export const label = value => typeof value === 'string' && value.length >= 1 && value.length <= 160 &&
  /^[\p{L}\p{N} ._()+-]+$/u.test(value) && !mac.test(value);
export const nullable = (value, predicate) => value === null || predicate(value);
export const oneOf = values => value => values.includes(value);
export function checkFields(value, fields) {
  object(value, Object.keys(fields));
  for (const [key, predicate] of Object.entries(fields)) requireValid(predicate(value[key]));
}
export function validatePing(value) {
  checkFields(value, {
    schemaVersion: v => v === 1,
    appVersion: v => typeof v === 'string' && v.length <= 80 && /^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(v),
    channel: oneOf(['github', 'play', 'fdroid', 'other']),
    androidVersion: label, manufacturer: label, model: label, romBuild: label,
    bitPerfectMixer: v => nullable(v, boolean), recentUsbAudio: boolean,
    firstToday: boolean, firstThisWeek: boolean, firstThisMonth: boolean,
  });
  requireValid(value.firstToday || (!value.firstThisWeek && !value.firstThisMonth));
  return value;
}

/** Bounds streaming bodies too; Content-Length alone is not a size boundary. */
export async function readBoundedText(body, maximum, timeoutMs = 5000, status = 413) {
  if (!body) throw new InvalidRequest();
  const reader = body.getReader();
  let timedOut = false;
  const deadline = setTimeout(() => {
    timedOut = true;
    void reader.cancel().catch(() => {}); // Cancellation may race an already closed stream.
  }, timeoutMs);
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const {value, done} = await reader.read();
      if (timedOut) throw new InvalidRequest(408);
      if (done) break;
      size += value.byteLength;
      if (size > maximum) { await reader.cancel(); throw new InvalidRequest(status); }
      chunks.push(value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    try { return new TextDecoder('utf-8', {fatal: true}).decode(bytes); }
    catch { throw new InvalidRequest(); }
  } finally { clearTimeout(deadline); reader.releaseLock(); }
}

/**
 * Whether valid JSON text names a property twice in one object, comparing names after unescaping.
 * JSON.parse keeps only the last value, so an earlier one would sit in the text unvalidated.
 */
export function hasDuplicateKeys(text) {
  const objects = []; // One set of names per open object, null per open array.
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '{') objects.push(new Set());
    else if (c === '[') objects.push(null);
    else if (c === '}' || c === ']') objects.pop();
    else if (c === '"') {
      let end = i + 1;
      while (text[end] !== '"') end += text[end] === '\\' ? 2 : 1;
      const names = objects.at(-1);
      let next = end + 1;
      while (text[next] === ' ' || text[next] === '\t' || text[next] === '\n' || text[next] === '\r') next++;
      if (names && text[next] === ':') {
        const name = JSON.parse(text.slice(i, end + 1));
        if (names.has(name)) return true;
        names.add(name);
      }
      i = end;
    }
  }
  return false;
}

/**
 * A client request: JSON only, never compressed, each property named once. Returns the parsed
 * value and the exact text, so storing the text stores nothing the value did not show (#170 P1).
 */
export async function readJsonRequest(request, maximum, timeoutMs = 5000) {
  if (request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json') throw new InvalidRequest(415);
  if (request.headers.has('content-encoding')) throw new InvalidRequest(415);
  const length = request.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > maximum)) throw new InvalidRequest(413);
  const text = await readBoundedText(request.body, maximum, timeoutMs);
  let value;
  try { value = JSON.parse(text); }
  catch { throw new InvalidRequest(); }
  requireValid(!hasDuplicateKeys(text));
  return {value, text};
}

export async function readJson(request, maximum, timeoutMs = 5000) {
  return (await readJsonRequest(request, maximum, timeoutMs)).value;
}
