"""Export only PNG trace artifacts of an explicit QA session, without auth data."""
import argparse, json, re, uuid
from pathlib import Path
from collect import Device, write_text

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',required=True); p.add_argument('--serial',required=True)
    p.add_argument('--package',default='dev.androidagent.app.dev')
    p.add_argument('--session-id',required=True); p.add_argument('--trace',required=True)
    p.add_argument('--out',required=True); a=p.parse_args()
    if str(uuid.UUID(a.session_id))!=a.session_id: p.error('Canonical session UUID required')
    paths=set()
    for line in Path(a.trace).read_text(encoding='utf-8').splitlines():
        path=json.loads(line).get('imageArtifact')
        if path:
            if not re.fullmatch(r'trace-artifacts/[0-9a-f-]{36}\.png',path): p.error('Unexpected artifact path')
            paths.add(path)
    root=Path(a.out)
    if root.exists(): p.error('Output exists; choose a new folder')
    root.mkdir(parents=True)
    d=Device(a); manifest=[]
    for path in sorted(paths):
        data=d.call('exec-out','run-as',a.package,'cat','files/sessions/'+a.session_id+'/workspace/'+path)
        if not data.startswith(b'\x89PNG\r\n\x1a\n') or len(data)>5*1024*1024: raise RuntimeError('Invalid or oversized PNG')
        target=root/path; target.parent.mkdir(exist_ok=True); target.write_bytes(data)
        manifest.append({'path':path,'bytes':len(data)})
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'session':a.session_id,'pngs':len(manifest),'folder':str(root.resolve())}))

if __name__=='__main__': main()
