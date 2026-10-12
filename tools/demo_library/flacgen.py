"""Minimal FLAC writer (fixed order-2 predictor, Rice-coded residuals) for synthetic demo tracks."""
import hashlib, struct, sys
import numpy as np

BLOCK = 4096
RATE_CODE = {44100: 0b1001, 48000: 0b1010, 96000: 0b1011}
BPS_CODE = {16: 0b100, 24: 0b110}

def _crc_table(poly, width):
    top = 1 << (width - 1); mask = (1 << width) - 1; table = []
    for b in range(256):
        c = b << (width - 8)
        for _ in range(8):
            c = ((c << 1) ^ poly) if c & top else (c << 1)
        table.append(c & mask)
    return table
CRC8 = _crc_table(0x07, 8); CRC16 = _crc_table(0x8005, 16)
def crc8(data):
    c = 0
    for b in data: c = CRC8[c ^ b]
    return c
def crc16(data):
    c = 0
    for b in data: c = ((c << 8) & 0xFFFF) ^ CRC16[(c >> 8) ^ b]
    return c

def utf8_num(n):
    if n < 0x80: return bytes([n])
    for length, lead in ((2, 0xC0), (3, 0xE0), (4, 0xF0), (5, 0xF8), (6, 0xFC), (7, 0xFE)):
        if n < (1 << (5 * length + 1)) or length == 7:
            out = []
            for _ in range(length - 1): out.append(0x80 | (n & 0x3F)); n >>= 6
            out.append(lead | n)
            return bytes(reversed(out))

def bits_of(value, width):
    """MSB-first bits of value (two's complement within width)."""
    v = np.asarray(value, dtype=np.int64) & ((1 << width) - 1)
    shifts = np.arange(width - 1, -1, -1, dtype=np.int64)
    return ((v[..., None] >> shifts) & 1).astype(np.uint8).reshape(-1)

def rice_bits(residual):
    u = np.where(residual >= 0, residual << 1, ((-residual) << 1) - 1).astype(np.int64)
    best = None
    for k in range(0, 15):
        total = int((u >> k).sum()) + len(u) * (k + 1)
        if best is None or total < best[0]: best = (total, k)
    total, k = best
    q = u >> k
    lengths = q + 1 + k
    starts = np.concatenate(([0], np.cumsum(lengths)[:-1]))
    out = np.zeros(total, dtype=np.uint8)
    out[starts + q] = 1
    if k:
        shifts = np.arange(k - 1, -1, -1, dtype=np.int64)
        low = ((u[:, None] >> shifts) & 1).astype(np.uint8)
        idx = (starts + q + 1)[:, None] + np.arange(k)
        out[idx.reshape(-1)] = low.reshape(-1)
    return np.concatenate([bits_of(0b00, 2), bits_of(0, 4), bits_of(k, 4), out])

def subframe(x, bps):
    x = x.astype(np.int64)
    residual = x[2:] - 2 * x[1:-1] + x[:-2]
    return np.concatenate([bits_of(0x14, 8), bits_of(x[:2], bps), rice_bits(residual)])

def encode(path, pcm, rate, bps, tags, picture_png, size):
    """pcm: int array (frames, 2) already scaled to bps."""
    frames = pcm.shape[0]; assert frames % BLOCK == 0
    width_bytes = bps // 8
    md5 = hashlib.md5(pcm.astype('<i4').view(np.uint8).reshape(-1, 4)[:, :width_bytes].tobytes()).digest()
    si = (BLOCK << 16 | BLOCK).to_bytes(4, 'big') + (0).to_bytes(6, 'big')
    packed = (rate << 44) | (1 << 41) | ((bps - 1) << 36) | frames
    si += packed.to_bytes(8, 'big') + md5
    vendor = b'vesqen-demo'
    comments = [f"{k}={v}".encode() for k, v in tags.items()]
    vc = struct.pack('<I', len(vendor)) + vendor + struct.pack('<I', len(comments)) + b''.join(struct.pack('<I', len(c)) + c for c in comments)
    mime = b'image/png'
    pic = struct.pack('>II', 3, len(mime)) + mime + struct.pack('>I', 0) + struct.pack('>IIIII', size, size, 24, 0, len(picture_png)) + picture_png
    def block(kind, data, last=False): return bytes([(0x80 if last else 0) | kind]) + len(data).to_bytes(3, 'big') + data
    out = bytearray(b'fLaC') + block(0, si) + block(4, vc) + block(6, pic, last=True)
    for n in range(frames // BLOCK):
        chunk = pcm[n * BLOCK:(n + 1) * BLOCK]
        header = bytes([0xFF, 0xF8, (0b1100 << 4) | RATE_CODE[rate], (0b0001 << 4) | (BPS_CODE[bps] << 1)]) + utf8_num(n)
        header += bytes([crc8(header)])
        bits = np.concatenate([subframe(chunk[:, 0], bps), subframe(chunk[:, 1], bps)])
        body = np.packbits(bits).tobytes()
        frame = header + body
        out += frame + crc16(frame).to_bytes(2, 'big')
    open(path, 'wb').write(out)

NOTE = lambda name: 440.0 * 2 ** ((name - 69) / 12)

def synth(seconds, rate, bps, chords, tempo_s, seed):
    """Soft pad chords with a slow melody; deterministic, no noise."""
    rng = np.random.default_rng(seed)
    n = int(seconds * rate); n -= n % BLOCK
    t = np.arange(n) / rate
    left = np.zeros(n); right = np.zeros(n)
    seg = int(tempo_s * rate)
    for i, start in enumerate(range(0, n, seg)):
        chord = chords[i % len(chords)]
        end = min(n, start + seg + rate // 2)
        tt = t[start:end] - t[start]
        env = np.minimum(1, tt / 0.8) * np.exp(-tt / (tempo_s * 1.6))
        for j, note in enumerate(chord):
            f = NOTE(note)
            tone = np.sin(2 * np.pi * f * tt) + 0.25 * np.sin(2 * np.pi * 2 * f * tt)
            pan = 0.35 + 0.3 * (j / max(1, len(chord) - 1))
            left[start:end] += env * tone * (1 - pan); right[start:end] += env * tone * pan
        mel = chord[-1] + 12 + int(rng.choice([0, 2, 4, 7]))
        mt = tt[: int(tempo_s * rate * 0.5)]
        menv = np.minimum(1, mt / 0.05) * np.exp(-mt / 0.9)
        m = 0.35 * menv * np.sin(2 * np.pi * NOTE(mel) * mt)
        left[start:start + len(m)] += m; right[start:start + len(m)] += m * 0.8
    fade = np.minimum(1, np.minimum(t / 2.0, (t[-1] - t) / 3.0))
    mix = np.stack([left, right], axis=1) * fade[:, None]
    mix /= np.abs(mix).max()
    full = (1 << (bps - 1)) - 1
    return np.round(mix * 0.5 * full).astype(np.int32)

if __name__ == '__main__':
    out_dir, cover_dir = sys.argv[1], sys.argv[2]
    albums = [
        ("Low Tide Archive", "Maren Holt", "Ambient", "tide", 96000, 24, [
            ("Glass Harbor", 158, [[57, 60, 64, 67], [53, 57, 60, 64], [55, 59, 62, 67], [52, 55, 59, 62]], 4.0),
            ("Salt Line", 186, [[50, 54, 57, 62], [55, 59, 62, 66], [52, 55, 59, 64], [57, 61, 64, 69]], 4.5),
            ("Night Ferry", 141, [[48, 52, 55, 60], [45, 48, 52, 57], [50, 53, 57, 62], [43, 47, 50, 55]], 5.0)]),
        ("Copper Hours", "Otis Vale Quartet", "Jazz", "copper", 44100, 16, [
            ("Brass Lantern", 204, [[50, 53, 57, 60], [55, 59, 62, 65], [48, 52, 55, 59], [57, 60, 64, 67]], 2.5),
            ("Copper Rain", 177, [[53, 57, 60, 64], [50, 53, 57, 60], [55, 58, 62, 65], [48, 52, 55, 58]], 2.4),
            ("Late Set", 232, [[46, 50, 53, 57], [51, 55, 58, 62], [44, 48, 51, 55], [53, 56, 60, 63]], 2.8),
            ("Smoke Ring", 165, [[52, 55, 59, 62], [57, 60, 64, 67], [50, 54, 57, 60], [55, 59, 62, 66]], 2.2)]),
        ("盐与雾", "林汐", "Folk", "fog", 48000, 24, [
            ("晚风测绘", 193, [[55, 59, 62], [52, 55, 59], [48, 52, 55], [50, 54, 57]], 3.2),
            ("潮汐表", 168, [[57, 60, 64], [53, 57, 60], [55, 59, 62], [52, 55, 59]], 3.4),
            ("雾中信号", 211, [[50, 53, 57], [55, 58, 62], [53, 57, 60], [48, 52, 55]], 3.6)]),
    ]
    import os
    for album, artist, genre, key, rate, bps, tracks in albums:
        cover = open(f"{cover_dir}/cover-{key}.png", 'rb').read()
        d = f"{out_dir}/{artist} - {album}"; os.makedirs(d, exist_ok=True)
        for i, (title, secs, chords, tempo) in enumerate(tracks, 1):
            pcm = synth(secs, rate, bps, chords, tempo, seed=i * 7 + rate)
            tags = {"TITLE": title, "ARTIST": artist, "ALBUM": album, "ALBUMARTIST": artist, "TRACKNUMBER": str(i), "DATE": "2025", "GENRE": genre}
            encode(f"{d}/{i:02d} {title}.flac", pcm, rate, bps, tags, cover, 600)
            print(f"{d}/{i:02d} {title}.flac", os.path.getsize(f"{d}/{i:02d} {title}.flac") // 1024, "KiB", flush=True)
