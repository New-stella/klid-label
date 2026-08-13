import json,os,urllib.request,uuid
_cfg=json.load(open(os.path.expanduser('~/.claude.json'),encoding='utf-8'))
def _find(o):
    if isinstance(o,dict):
        if 'logicraft' in o.get('mcpServers',{}): return o['mcpServers']['logicraft']
        for v in o.values():
            r=_find(v)
            if r: return r
    return None
_s=_find(_cfg)
URL=_s.get('url') or _s.get('endpoint')
HDR=dict(_s.get('headers',{}))
BASE=URL.rsplit('/mcp',1)[0]
PID='4ece2c3f-8e99-46f5-9580-71108a76e578'
_sid=None
def _post(body):
    global _sid
    h=dict(HDR); h['Content-Type']='application/json'
    h['Accept']='application/json, text/event-stream'
    if _sid: h['Mcp-Session-Id']=_sid
    req=urllib.request.Request(URL,data=json.dumps(body).encode('utf-8'),headers=h,method='POST')
    r=urllib.request.urlopen(req,timeout=180)
    if not _sid and r.headers.get('Mcp-Session-Id'): _sid=r.headers.get('Mcp-Session-Id')
    raw=r.read().decode('utf-8')
    if raw.lstrip().startswith('{'): return json.loads(raw)
    out=None
    for line in raw.splitlines():
        if line.startswith('data:'):
            try: out=json.loads(line[5:].strip())
            except Exception: pass
    return out
def init():
    _post({"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"pm","version":"1"}}})
    h=dict(HDR); h['Content-Type']='application/json'; h['Accept']='application/json, text/event-stream'
    if _sid: h['Mcp-Session-Id']=_sid
    try:
        urllib.request.urlopen(urllib.request.Request(URL,data=json.dumps({"jsonrpc":"2.0","method":"notifications/initialized"}).encode(),headers=h,method='POST'),timeout=30)
    except Exception: pass
def call(name,args):
    res=_post({"jsonrpc":"2.0","id":str(uuid.uuid4()),"method":"tools/call","params":{"name":name,"arguments":args}})
    return res
