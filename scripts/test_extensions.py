#!/usr/bin/env python3
"""Real media processing and custom-policy contract tests using Python stdlib."""
import sys,json,urllib.request,urllib.error,base64,struct,zlib
base=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8099'
def send(method,path,payload=None,status=200):
 d=json.dumps(payload).encode() if payload is not None else None
 req=urllib.request.Request(base+path,data=d,method=method,headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=4) as r:code=r.status;res=json.loads(r.read())
 except urllib.error.HTTPError as e:code=e.code;res=json.loads(e.read())
 assert code==status,(code,status,res);return res

def chunk(tag,data):return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data)&0xffffffff)
width,height=64,48
scanlines=b''.join(b'\x00'+bytes([21,150,125])*width for _ in range(height))
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(scanlines))+chunk(b'IEND',b'')
encoded=base64.b64encode(png).decode()
asset=send('POST','/api/media',{'name':'QA test asset.png','mime':'image/png','base64':encoded,'altText':'A green rectangular test image'},201)
assert asset['width']==64 and asset['height']==48 and asset['status']=='Ready'
assert send('GET','/api/media')[0]['id']==asset['id']
assert send('GET','/api/media/'+asset['id'])['base64']==encoded
assert send('POST','/api/media',{'name':'Bad image','mime':'image/jpeg','base64':encoded},415)['error']
print('✓ PNG upload persisted, decoded dimensions, mime enforcement, byte retrieval')
phrase='always double '+str(len(send('GET','/api/policies')['customRules'])+1)
policy=send('POST','/api/policies/rules',{'title':'Disallow unsupported savings guarantee','phrase':phrase,'severity':'High'},201)
assert '+r' in policy['policyVersion']
assert send('GET','/api/policies')['activeCustomRules']>=1
assert send('POST','/api/policies/rules',{'title':'Duplicate','phrase':phrase,'severity':'High'},409)
draft=send('POST','/api/drafts',{'title':'Testing custom policy','body':'Our savings '+policy['rule']['phrase']+' with every deposit.'},201)
run=send('POST','/api/validations',{'draftId':draft['id'],'altText':'A product illustration','platform':'LinkedIn'},201)
assert any(f['id']==policy['rule']['id'] for f in run['findings'])
assert run['verdict']=='BLOCKED'
print('✓ Custom rule created, versioned, enforced and duplicate rejected')
assert send('GET','/api/audit')['chainValid']
print('PASS — uploaded media and customizable policy engine')
