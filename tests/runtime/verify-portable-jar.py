"""Verify the distributable plugin's Java 8 base bytecode and exclude runtime-only fixtures."""
import collections
import pathlib
import struct
import sys
import zipfile

artifact = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'target/RegionItemSearch-1.6.0.jar')
levels = collections.Counter()
with zipfile.ZipFile(artifact) as jar:
    for name in jar.namelist():
        if '/probe/' in name or name.startswith('net/kyori/'):
            raise SystemExit('Unexpected runtime fixture or unrelocated library: ' + name)
        if not name.endswith('.class') or name.startswith('META-INF/versions/'):
            continue
        major = struct.unpack('>H', jar.read(name)[6:8])[0]
        levels[major] += 1
        if major > 52:
            raise SystemExit('Not Java 8 compatible: %s (class version %d)' % (name, major))
    metadata = jar.read('plugin.yml').decode('utf-8-sig')
    if 'folia-supported: true' not in metadata:
        raise SystemExit('Missing Folia marker')
    for resource in ('config.yml', 'lang.yml'):
        jar.read(resource).decode('utf-8-sig', errors='strict')
print('PASS: Java 8 base classes %s; UTF-8 resources; no runtime probes; relocated chat library' % dict(levels))