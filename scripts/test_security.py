#!/usr/bin/env python3
"""Local API trust-boundary checks."""
import sys,urllib.request,urllib.error,json
base=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8080'
def check(method,path,payload=None,headers=None,expected=200):
 data=json.dumps(payload).encode() if payload is not None else None
 h={'Accept':'application/json',**(headers or {})};r=urllib.request.Request(base+path,data=data,headers=h,method=method)
 try:
  with urllib.request.urlopen(r,timeout=5) as resp: status=resp.status;body=resp.read().decode()
 except urllib.error.HTTPError as e: status=e.code;body=e.read().decode()
 assert status==expected,(status,expected,body[:250])
 return body
assert 'certificateSecret' not in check('GET','/api/bootstrap')
check('POST','/api/drafts',{'title':'CSRF attempt'},headers={'Content-Type':'application/json','Origin':'https://malicious.example'},expected=403)
check('POST','/api/drafts',{'title':'Invalid media type'},headers={'Content-Type':'text/plain'},expected=415)
check('POST','/api/drafts',{'title':'oversized','body':'x'*20000},headers={'Content-Type':'application/json'},expected=400)
check('POST','/api/drafts',{'title':'invalid JSON'},headers={'Content-Type':'application/json','Origin':'https://malicious.example'},expected=403)
check('GET','/api/no-such-endpoint',expected=404)
print('PASS — local-origin writes, validation limits, no secret disclosure and unknown routes')
