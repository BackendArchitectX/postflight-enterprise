#!/usr/bin/env python3
"""Fail-closed HTTP security tests for unconfigured and public Clerk workspaces."""
import json
import sys
import urllib.request
import urllib.error

PUBLIC=(sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:10003').rstrip('/')
PRIVATE=(sys.argv[2] if len(sys.argv)>2 else 'http://127.0.0.1:10004').rstrip('/')

def call(base,method,path,body=None):
    headers={'Accept':'application/json'}
    if body is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(base+path,method=method,headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request,timeout=12) as res:return res.status,json.load(res)
    except urllib.error.HTTPError as error:return error.code,json.loads(error.read().decode())

status,cfg=call(PUBLIC,'GET','/api/secure/config')
assert status==200 and cfg['enabled'] is False and cfg['publicReadOnly'] is True, (status,cfg)
status,body=call(PUBLIC,'GET','/api/secure/workspaces')
assert status==503, (status,body)
status,body=call(PUBLIC,'POST','/api/secure/workspaces',{'name':'Should not create'})
assert status==403, (status,body)
status,body=call(PUBLIC,'POST','/api/secure/invitations/accept',{
    'invitationId':'inv_test','inviteToken':'test'})
assert status==403, (status,body)
status,cfg=call(PRIVATE,'GET','/api/secure/config')
assert status==200 and cfg['publicReadOnly'] is False
status,body=call(PRIVATE,'GET','/api/secure/me')
assert status in (401,503), (status,body)
status,body=call(PRIVATE,'GET','/api/secure/workspaces')
assert status in (401,503), (status,body)
status,body=call(PRIVATE,'POST','/api/secure/workspaces',{'name':'Anonymous attacker'})
assert status in (401,503), (status,body)
status,body=call(PRIVATE,'POST','/api/secure/workspaces/ws_00000000000000000000000000000000/invitations',{
    'targetSub':'a8de7ff2-3b87-4e47-a319-dd8ce1168e90','role':'ADMIN'})
assert status in (401,503), (status,body)
status,body=call(PRIVATE,'GET','/api/bootstrap')
assert status==200 and 'secure-workspaces' not in json.dumps(body), (
    status,'Shared demo bootstrap disclosed secure workspace records')
print('PASS - public secure-workspace routes fail closed; no mutation in public mode')
print('PASS - anonymous local workspace and membership requests denied')
print('PASS - private workspace records never enter shared demo bootstrap')
