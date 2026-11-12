import glob, os, sys, zipfile
import xml.etree.ElementTree as ET

root = sys.argv[1]
tot = {}
for f in glob.glob(root + '/**/test-results/**/TEST-*.xml', recursive=True):
    rel = os.path.relpath(f, root).replace(os.sep, '/')
    mod, rest = rel.split('/test-results/')
    task = rest.split('/')[0]
    r = ET.parse(f).getroot()
    d = tot.setdefault((mod, task), [0, 0, 0, 0])
    d[0] += int(r.get('tests'))
    d[1] += int(r.get('failures'))
    d[2] += int(r.get('errors'))
    d[3] += int(r.get('skipped'))
s = [0, 0, 0, 0]
for k, v in sorted(tot.items()):
    print(k, 'tests=%d fail=%d err=%d skip=%d' % tuple(v))
    s = [a + b for a, b in zip(s, v)]
print('TOTAL tests=%d fail=%d err=%d skip=%d' % tuple(s))

for apk in sys.argv[2:]:
    z = zipfile.ZipFile(apk)
    big = sorted(z.infolist(), key=lambda i: -i.file_size)[:14]
    print('\n', apk, os.path.getsize(apk))
    for i in big:
        print('  %11d %11d %s' % (i.file_size, i.compress_size, i.filename))
