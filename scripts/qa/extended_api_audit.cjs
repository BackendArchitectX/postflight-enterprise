'use strict';
// Extra security/contract checks against the *disposable* PostFlight QA pair only.
// This script must never be directed at port 8080, production, or live Clerk.
const fs = require('fs');
const path = require('path');
const { performance } = require('perf_hooks');
const root = process.env.PF_QA_OUTPUT;
const demo = process.env.PF_QA_DEMO;
const edit = process.env.PF_QA_EDIT;
const results = {schemaVersion:1,scope:'disposable-local-api',checks:[],metrics:{},errors:[]};
const forbid = (url,port)=>{
  if (!url) throw Error('Missing isolated QA URL');
  const u = new URL(url);
  if (!['127.0.0.1','localhost'].includes(u.hostname) || u.protocol!=='http:' || u.port!==String(port) || u.pathname!=='/')
    throw Error('Refusing non-disposable target: '+u.origin);
  return u.origin;
};
function assert(v,m){if(!v)throw Error(m);}
async function check(name,fn){
  try{await fn();results.checks.push({name,status:'PASS'});process.stdout.write('[PASS] '+name+'\n');}
  catch(e){results.checks.push({name,status:'FAIL',detail:String(e.message||e)});results.errors.push(name+': '+String(e.message||e));process.stderr.write('[FAIL] '+name+' — '+String(e.message||e)+'\n');}
}
async function request(base,method,p,body,raw=false){
  const headers={Accept:'application/json'};
  const init={method,headers,redirect:'manual',signal:AbortSignal.timeout(8000)};
  if(body!==undefined){headers['Content-Type']='application/json';init.body=raw?body:JSON.stringify(body);}
  const start=performance.now();
  const res=await fetch(base+p,init);
  const text=await res.text();
  let parsed=null;
  try{parsed=JSON.parse(text);}catch{}
  return {status:res.status,data:parsed,raw:text,ms:performance.now()-start};
}
(async()=>{
  try{
    const publicUrl=forbid(demo,10003),editUrl=forbid(edit,10004);
    assert(root && path.isAbsolute(root),'PF_QA_OUTPUT must be an absolute path');
    fs.mkdirSync(root,{recursive:true});
    const health=await Promise.all([request(publicUrl,'GET','/api/health'),request(editUrl,'GET','/api/health')]);
    assert(health.every(x=>x.status===200 && x.data?.status==='UP'),'Disposable servers are not healthy');
    const publicBootstrap=await request(publicUrl,'GET','/api/bootstrap');
    const editBootstrap=await request(editUrl,'GET','/api/bootstrap');
    assert(publicBootstrap.status===200 && publicBootstrap.data?.runtime?.mode==='public-read-only','Public server not read-only');
    assert(editBootstrap.status===200 && editBootstrap.data?.runtime?.mode==='local-development','Editable server not isolated local-development');
    const publicDrafts=JSON.stringify(publicBootstrap.data.drafts),publicAlerts=JSON.stringify(publicBootstrap.data.alerts);
    await check('Anonymous secure workspace endpoints fail closed',async()=>{
      for(const server of [publicUrl,editUrl]){
        for(const p of ['/api/secure/me','/api/secure/workspaces','/api/secure/workspaces/ws_test/drafts']){
          const r=await request(server,'GET',p);
          assert([401,403,404,503].includes(r.status),p+' returned HTTP '+r.status);
        }
      }
    });
    const denied=[
      ['POST','/api/admin/reset',{}],
      ['POST','/api/drafts',{title:'Should not save'}],
      ['PUT','/api/drafts/PF-0000',{body:'Should not change'}],
      ['POST','/api/validations',{draftId:'PF-0000'}],
      ['POST','/api/reviews/PF-0000/decision',{decision:'Approved'}],
      ['POST','/api/approvals/requests',{draftId:'PF-0000'}],
      ['POST','/api/releases',{draftId:'PF-0000'}],
      ['POST','/api/policies/rules',{title:'No',phrase:'No'}],
      ['POST','/api/policies/publications',{}],
      ['POST','/api/incidents',{title:'No'}],
      ['PATCH','/api/incidents/PF-INC-0000',{status:'Resolved'}],
      ['POST','/api/workflows/test',{}],
      ['POST','/api/policies/simulate',{}],
      ['POST','/api/media',{}],
    ];
    for(const [method,p,body] of denied){
      await check('Public mutation denied: '+method+' '+p,async()=>{
        const r=await request(publicUrl,method,p,body);
        assert(r.status===403,'Expected HTTP 403, got '+r.status);
      });
    }
    await check('Public seeded records unaffected by attempted mutations',async()=>{
      const r=await request(publicUrl,'GET','/api/bootstrap');
      assert(r.status===200,'Public bootstrap failed');
      assert(JSON.stringify(r.data.drafts)===publicDrafts,'Public drafts mutated');
      assert(JSON.stringify(r.data.alerts)===publicAlerts,'Public incidents mutated');
    });
    await check('Invalid JSON fails without write',async()=>{
      const r=await request(editUrl,'POST','/api/drafts','{"title":',true);
      assert(r.status===400,'Expected HTTP 400, got '+r.status);
    });
    await check('Oversized draft title rejected',async()=>{
      const r=await request(editUrl,'POST','/api/drafts',{title:'x'.repeat(500)});
      assert(r.status===400,'Expected HTTP 400, got '+r.status);
    });
    await check('Missing draft title rejected',async()=>{
      const r=await request(editUrl,'POST','/api/drafts',{body:'orphan'});
      assert(r.status===400,'Expected HTTP 400, got '+r.status);
    });
    await check('Unknown API routes fail closed',async()=>{
      const r=await request(editUrl,'GET','/api/__nonexistent_route__');
      assert(r.status===404,'Expected HTTP 404, got '+r.status);
    });
    await check('Invalid incident state rejected',async()=>{
      const r=await request(editUrl,'PATCH','/api/incidents/nonexistent',{status:'Published'});
      assert(r.status===400,'Expected HTTP 400, got '+r.status);
    });
    await check('Optimistic draft version conflict prevents lost update',async()=>{
      const created=await request(editUrl,'POST','/api/drafts',{title:'Extended QA concurrency canary',body:'version one',platforms:'LinkedIn'});
      assert(created.status===201,'Canary draft create failed: '+created.status);
      const id=encodeURIComponent(created.data.id),version=created.data.version;
      const [a,b]=await Promise.all([
        request(editUrl,'PUT','/api/drafts/'+id,{expectedVersion:version,body:'Writer A'}),
        request(editUrl,'PUT','/api/drafts/'+id,{expectedVersion:version,body:'Writer B'})
      ]);
      const statuses=[a.status,b.status].sort((x,y)=>x-y).join(',');
      assert(statuses==='200,409','Expected one accepted and one conflict, got '+statuses);
      const after=await request(editUrl,'GET','/api/bootstrap');
      const actual=after.data.drafts.find(x=>x.id===created.data.id);
      assert(actual && actual.version===version+1,'Expected exactly one version increment');
      assert(['Writer A','Writer B'].includes(actual.body),'Unexpected draft content after conflict');
    });
    await check('Reject stale approval without validation',async()=>{
      const created=await request(editUrl,'POST','/api/drafts',{title:'Extended QA unvalidated review',body:'No validation'});
      assert(created.status===201,'Canary draft create failed');
      const r=await request(editUrl,'POST','/api/reviews/'+encodeURIComponent(created.data.id)+'/decision',{decision:'Approved'});
      assert(r.status===409,'Expected HTTP 409, got '+r.status);
    });
    await check('Audit chain remains valid after extended checks',async()=>{
      const r=await request(editUrl,'GET','/api/audit');
      assert(r.status===200&&r.data?.chainValid===true,'Audit chain invalid');
    });
    await check('Public API health latency sample (informational only)',async()=>{
      const times=[];
      for(let i=0;i<12;i++){
        const r=await request(publicUrl,'GET','/api/health');
        assert(r.status===200,'Health check failed');times.push(r.ms);
      }
      times.sort((a,b)=>a-b);
      results.metrics.healthRequest={sampleCount:times.length,p50Ms:Math.round(times[5]*100)/100,p95Ms:Math.round(times[11]*100)/100,threshold:'none; diagnostic sample, not a load test'};
    });
  }catch(e){results.errors.push('SETUP: '+String(e.stack||e));results.checks.push({name:'Extended API setup',status:'FAIL',detail:String(e.message||e)});}
  finally{
    results.completedAt=new Date().toISOString();
    if(root && path.isAbsolute(root)){
      try{fs.mkdirSync(root,{recursive:true});fs.writeFileSync(path.join(root,'extended-api.json'),JSON.stringify(results,null,2),'utf8');}catch(e){process.stderr.write('REPORT ERROR '+e.message+'\n');process.exitCode=1;}
    }
    const pass=results.checks.filter(x=>x.status==='PASS').length,fail=results.checks.filter(x=>x.status==='FAIL').length;
    process.stdout.write('EXTENDED API: '+pass+' PASS, '+fail+' FAIL, '+results.errors.length+' errors\n');
    if(fail || results.errors.length)process.exitCode=1;
  }
})();
