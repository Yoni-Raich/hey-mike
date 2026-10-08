"""Scoped QA case driver. No UI dump during a run, no automatic replay after uncertain Send."""
import argparse
import base64
import json
from pathlib import Path
import re
import time
import uuid
import xml.etree.ElementTree as ET
from collect import Device
from analyze_trace import analyze
from latency import agent_stats, frame_stats, input_draw


class Runner:
    def __init__(self, args):
        self.args, self.device = args, Device(args)
        self.root=Path(args.out); self.root.mkdir(parents=True,exist_ok=True)

    def state(self, session=None):
        args=['shell','am','broadcast','-n',self.args.package+'/dev.androidagent.app.QaEvidenceReceiver']
        if session: args+=['--es','session_id',str(uuid.UUID(session))]
        output=self.device.text(*args)
        match=re.search(r'data="(\{.*\})"',output,re.S)
        if not match: raise RuntimeError('Debug QA receiver did not answer; no action dispatched')
        data=json.loads(match[1])
        if not data.get('ok'): raise RuntimeError('QA state unavailable')
        return data

    def idle(self):
        state=self.state()
        if state['runs']: raise RuntimeError('Active runs; UI inspection/action refused')
        return state

    def xml(self):
        self.idle()
        self.device.call('shell','uiautomator','dump','/sdcard/qa-mike-idle.xml',timeout=15)
        return ET.fromstring(self.device.text('exec-out','cat','/sdcard/qa-mike-idle.xml'))

    def tap(self, label):
        root=self.xml()
        matches=[n for n in root.iter('node') if label in (n.get('text'),n.get('content-desc'))]
        if len(matches)!=1: raise RuntimeError('Idle selector is absent/ambiguous: '+label)
        self.tap_node(matches[0])

    def tap_node(self,node):
        self.idle()
        values=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
        if len(values)!=4: raise RuntimeError('Invalid bounds')
        x1,y1,x2,y2=values
        self.device.call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

    def checkpoint(self,path,data):
        temporary=path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
        temporary.replace(path)

    def prompt(self,text,case_id):
        if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',case_id): raise ValueError('Invalid case id')
        path=self.root/(case_id+'.checkpoint.json')
        if path.exists(): raise RuntimeError('Case already checkpointed. Inspect/resume its existing session; never resend automatically')
        state=self.idle(); session=state['activeSessionId']
        existing=self.state(session).get('draft','')
        if existing: raise RuntimeError('Existing draft; no replacement or Send')
        root=self.xml()
        editor=next((n for n in root.iter('node') if n.get('content-desc')=='Message input' and n.get('package')==self.args.package),None)
        send=next((n for n in root.iter('node') if n.get('content-desc')=='Send message'),None)
        if editor is None: raise RuntimeError('Mike composer not on screen')
        self.tap_node(editor)
        original=self.device.text('shell','settings','get','secure','default_input_method').strip()
        ime=self.args.package+'/dev.androidagent.app.ime.AgentInputMethodService'
        enabled=ime in self.device.text('shell','settings','get','secure','enabled_input_methods')
        record={'case':case_id,'sessionId':session,'phase':'preparing','prompt':text,'a11yBoundBeforeInput':state['a11yBound']}
        self.checkpoint(path,record)
        try:
            if not enabled: self.device.call('shell','ime','enable',ime)
            self.device.call('shell','ime','set',ime)
            time.sleep(.75)
            reply=self.device.text('shell','am','broadcast','-a',self.args.package+'.INPUT_TEXT','-p',self.args.package,
                '--es','payload_base64',base64.b64encode(text.encode()).decode())
            if 'result=1' not in reply: raise RuntimeError('IME did not acknowledge; no Send')
            deadline=time.monotonic()+8
            while self.state(session).get('draft')!=text:
                if time.monotonic()>deadline: raise RuntimeError('Exact persisted draft mismatch; no Send')
                time.sleep(.2)
            # Keyboard geometry can change. Inspect only while idle, then wait for service rebind.
            root=self.xml()
            send=next(n for n in root.iter('node') if n.get('content-desc')=='Send message')
            deadline=time.monotonic()+10
            while state['a11yBound'] and not self.idle()['a11yBound']:
                if time.monotonic()>deadline: raise RuntimeError('Service did not rebind after idle dump')
                time.sleep(.25)
            record['phase']='dispatching'; record['exactDraftVerified']=True
            self.checkpoint(path,record)
            self.tap_node(send)
            record['phase']='sent'; self.checkpoint(path,record)
            return record
        finally:
            if original and original!='null': self.device.call('shell','ime','set',original)
            if not enabled: self.device.call('shell','ime','disable',ime)

    def wait(self,case_id,seconds):
        path=self.root/(case_id+'.checkpoint.json')
        record=json.loads(path.read_text(encoding='utf-8'))
        if record['phase'] not in ('sent','running','finished'): raise RuntimeError('Uncertain Send. Inspect; do not replay')
        if record['phase']=='finished': return record
        session=record['sessionId']; deadline=time.monotonic()+seconds
        saw_running=record['phase']=='running'
        while time.monotonic()<deadline:
            state=self.state()
            if session in state['runs']:
                saw_running=True
                if record['phase']!='running': record['phase']='running'; self.checkpoint(path,record)
            else:
                try: trace=self.device.text('exec-out','run-as',self.args.package,'cat','files/sessions/'+session+'/workspace/session-trace.jsonl')
                except RuntimeError:
                    time.sleep(1); continue
                events=[json.loads(line) for line in trace.splitlines() if line.strip()]
                users=[e for e in events if e.get('type')=='user']
                if not users or users[-1].get('text')!=record['prompt']:
                    if saw_running: raise RuntimeError('Trace prompt mismatch; invalid case')
                    time.sleep(1); continue
                start=users[-1]['timestampMs']; events=[e for e in events if e.get('timestampMs',0)>=start]
                if not any(e.get('type')=='turn_finished' or (e.get('type')=='assistant' and e.get('phase') in ('final_answer','error','stopped')) for e in events):
                    time.sleep(1); continue
                (self.root/(case_id+'.trace.jsonl')).write_text('\n'.join(json.dumps(e,ensure_ascii=False) for e in events)+'\n',encoding='utf-8')
                record.update(phase='finished',pairing=analyze(events),timing=agent_stats(events),
                    outcome='error' if any(e.get('phase')=='error' or e.get('type')=='turn_finished' and e.get('status')=='failed' for e in events) else 'review_required')
                self.checkpoint(path,record)
                return record
            time.sleep(1)
        raise RuntimeError('Wait timed out; existing session is retained, no new Send')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['state','tap-idle','prompt','wait','screen','latency-report'])
    p.add_argument('--adb',required=True); p.add_argument('--serial',required=True)
    p.add_argument('--package',default='dev.androidagent.app.dev'); p.add_argument('--out',required=True)
    p.add_argument('--label'); p.add_argument('--prompt-file'); p.add_argument('--case-id'); p.add_argument('--session-id')
    p.add_argument('--seconds',type=int,default=900); p.add_argument('--gfx'); p.add_argument('--log')
    a=p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_.]+',a.package): p.error('Invalid package')
    r=Runner(a)
    if a.mode=='state': result=r.state(a.session_id)
    elif a.mode=='tap-idle': r.tap(a.label); result={'tapped':a.label}
    elif a.mode=='prompt': result=r.prompt(Path(a.prompt_file).read_text(encoding='utf-8-sig'),a.case_id)
    elif a.mode=='wait': result=r.wait(a.case_id,a.seconds); result={k:v for k,v in result.items() if k not in ('prompt','pairing')}
    elif a.mode=='screen':
        target=r.root/(a.label+'.png')
        if target.exists(): raise RuntimeError('Screenshot exists')
        target.write_bytes(r.device.call('exec-out','screencap','-p')); result={'file':str(target)}
    else:
        result={'frames':frame_stats(Path(a.gfx).read_text()),'inputToDraw':input_draw(Path(a.log).read_text())}
        r.checkpoint(r.root/'ui-latency.json',result)
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__': main()
