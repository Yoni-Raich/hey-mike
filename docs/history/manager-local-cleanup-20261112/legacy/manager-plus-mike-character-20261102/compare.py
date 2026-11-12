import sys, zipfile

new, ref = sys.argv[1:3]
a = {i.filename: i for i in zipfile.ZipFile(new).infolist()}
b = {i.filename: i for i in zipfile.ZipFile(ref).infolist()}
pick = lambda d: {k for k in d if k.startswith('lib/') or k.startswith('assets/runtime/')}
print('native+runtime entries new/ref:', len(pick(a)), len(pick(b)))
print('missing from new:', sorted(pick(b) - pick(a)))
print('extra in new:', sorted(pick(a) - pick(b)))
print('size differs:', [k for k in sorted(pick(a) & pick(b)) if a[k].file_size != b[k].file_size])
for k in ('lib/arm64-v8a/libcodex_app_server.so', 'lib/arm64-v8a/libcodex_codemode.so', 'assets/runtime/cacert.pem'):
    print(k, k in a, a[k].file_size if k in a else None)
