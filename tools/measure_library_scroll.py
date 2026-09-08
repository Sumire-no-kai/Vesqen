"""ADB frame statistics from a UI-XML-derived viewport. Artifacts may contain private data.

Run with the library open and settled; use the same APK, data, power and play state for A/B.
Debug builds require explicit opt-in and are not a release performance acceptance result.
This measures HWUI jank, not audio underruns or a call-stack root cause.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--package', default='io.github.sumirenokai.vesqen')
parser.add_argument('--swipe-ms', type=int, choices=[100, 350], default=100)
parser.add_argument('--swipes', type=int, default=48)
parser.add_argument('--repeats', type=int, default=3)
parser.add_argument('--xml', type=Path, required=True)
parser.add_argument('--allow-debuggable', action='store_true', help='Allow an explicitly labelled Debug comparison')
args = parser.parse_args()
if args.output.exists(): parser.error('Output already exists; preserve previous evidence')
if args.swipes < 2 or args.repeats < 1: parser.error('Positive repeat count and at least two swipes required')
args.output.mkdir(parents=True)
adb = ['adb', '-s', args.serial]

def call(*cmd):
    return subprocess.check_output(adb + list(cmd), timeout=60).decode('utf-8', errors='replace')

def installed_apks():
    paths = [line.removeprefix('package:') for line in
             call('shell', 'pm', 'path', args.package).splitlines() if line.startswith('package:')]
    if not paths:
        raise RuntimeError('App is not installed')
    result = {}
    for path in paths:
        digest = call('shell', 'sha256sum', path).split()[0]
        if not re.fullmatch('[0-9a-fA-F]{64}', digest):
            raise RuntimeError('Could not verify installed APK identity')
        result[path] = digest.lower()
    return result

package_before = call('shell', 'dumpsys', 'package', args.package)
(args.output/'package-before.txt').write_text(package_before, encoding='utf-8')
apk_identity = installed_apks()
debuggable = bool(re.search(r'\bDEBUGGABLE\b', package_before))
(args.output/'build-identity.json').write_text(json.dumps({
    'apks': apk_identity, 'debuggable': debuggable,
}, indent=2), encoding='utf-8')
if debuggable and not args.allow_debuggable:
    raise RuntimeError('Debug APK installed: use profile for acceptance, or --allow-debuggable for a Debug comparison')

tree = ET.parse(args.xml)
if any('正在扫描' in n.get('text', '') or n.get('text', '').startswith('Scanning ') for n in tree.iter('node')):
    raise RuntimeError('Wait for the library scan to finish and capture a fresh XML viewport')
nodes = [n for n in tree.iter('node') if n.get('scrollable') == 'true' and n.get('package') == args.package]
def bounds(n): return list(map(int, re.findall(r'-?\d+', n.get('bounds', ''))))
if not nodes: raise RuntimeError('No app scroll viewport in XML')
x1, y1, x2, y2 = bounds(max(nodes, key=lambda n: bounds(n)[3] - bounds(n)[1]))
if y2 - y1 <= 400: raise RuntimeError('Expected vertical library viewport')
margin = (y2 - y1) // 6

def swipe(up):
    start, end = (y2-margin, y1+margin) if up else (y1+margin, y2-margin)
    call('shell', 'input', 'swipe', str((x1+x2)//2), str(start), str((x1+x2)//2), str(end), str(args.swipe_ms))

metadata = {'swipeMs': args.swipe_ms, 'viewport': [x1,y1,x2,y2], 'swipes': args.swipes,
            'apks': apk_identity, 'debuggable': debuggable,
            'model': call('shell','getprop','ro.product.model').strip(),
            'sdk': call('shell','getprop','ro.build.version.sdk').strip()}
for name, cmd in [('battery', ['dumpsys','battery']), ('display', ['dumpsys','display'])]:
    (args.output/f'{name}-before.txt').write_text(call('shell', *cmd), encoding='utf-8')
for i in range(4): swipe(i % 2 == 0)
results=[]
for repeat in range(args.repeats):
    call('shell','dumpsys','gfxinfo',args.package,'reset')
    start=time.monotonic()
    for i in range(args.swipes): swipe((i // 8) % 2 == 0)
    raw=call('shell','dumpsys','gfxinfo',args.package,'framestats')
    (args.output/f'frames-{repeat+1}.txt').write_text(raw, encoding='utf-8')
    row={'repeat':repeat+1,'elapsedSeconds':round(time.monotonic()-start,3)}
    for key in ['Total frames rendered','Janky frames','50th percentile','90th percentile','95th percentile','99th percentile']:
        match=re.search(re.escape(key)+r': (.+)',raw)
        row[key]=match.group(1) if match else None
    if not row['Total frames rendered'] or int(row['Total frames rendered']) <= 0:
        raise RuntimeError('No rendered frames; this is not a valid measurement')
    results.append(row)
    print(json.dumps(row), flush=True)
if installed_apks() != apk_identity:
    raise RuntimeError('Installed APK changed during measurement; this run is invalid')
(args.output/'battery-after.txt').write_text(call('shell', 'dumpsys', 'battery'), encoding='utf-8')
(args.output/'summary.json').write_text(json.dumps({'scenario':metadata,'results':results},indent=2), encoding='utf-8')
