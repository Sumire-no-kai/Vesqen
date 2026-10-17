"""Writes a large library of short silent WAV files for scroll tests and Baseline Profile runs (#50).

Titles mix Latin, Chinese and Japanese words so rows, sorting and the A-Z index see varied text.
Push the folder under /sdcard/Music on a test device; MediaStore lists the files as music.
"""
import argparse
from pathlib import Path
import struct

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
parser.add_argument('--count', type=int, default=2000)
parser.add_argument('--per-folder', type=int, default=20)
args = parser.parse_args()
if args.output.exists(): parser.error('Output already exists')
if args.count < 1 or args.per_folder < 1: parser.error('Positive counts required')

FIRST = ['Amber', 'Blue', 'Cedar', 'Distant', 'Echo', 'Faded', 'Glass', 'Harbor', 'Iron', 'Juniper',
         'Kite', 'Lantern', 'Morning', 'North', 'Opal', 'Paper', 'Quiet', 'River', 'Salt', 'Tidal',
         'Upper', 'Velvet', 'Winter', 'Xenon', 'Yellow', 'Zephyr', '夜の', '静かな', '远方的', '雨后']
SECOND = ['Avenue', 'Bells', 'Coast', 'Dunes', 'Embers', 'Fields', 'Garden', 'Hours', 'Island', 'Letters',
          '灯塔', '海风', '旅人', '星座', '回声', '窓辺', '坂道', '月光']
RATE, SECONDS = 8000, 0.5
frames = int(RATE * SECONDS)
pcm = bytes(frames * 2)
header = b'RIFF' + struct.pack('<I', 36 + len(pcm)) + b'WAVE' + b'fmt ' + struct.pack(
    '<IHHIIHH', 16, 1, 1, RATE, RATE * 2, 2, 16) + b'data' + struct.pack('<I', len(pcm))

for index in range(args.count):
    folder = args.output / f'Scroll Library {index // args.per_folder + 1:03d}'
    folder.mkdir(parents=True, exist_ok=True)
    title = f'{FIRST[index % len(FIRST)]} {SECOND[(index // len(FIRST)) % len(SECOND)]} {index + 1:04d}'
    (folder / f'{title}.wav').write_bytes(header + pcm)
print(f'Wrote {args.count} files to {args.output}')
