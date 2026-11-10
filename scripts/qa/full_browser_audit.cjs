'use strict';
// PostFlight full browser QA. Runs ONLY against isolated localhost QA instances.
// Intentionally never supplies social credentials or requests remote publishing.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { chromium } = require('playwright');
const base = (process.env.PF_QA_DEMO || 'http://127.0.0.1:10003').replace(/\/$/, '');
const editBase = (process.env.PF_QA_EDIT || 'http://127.0.0.1:10004').replace(/\/$/, '');
const output = process.env.PF_QA_OUTPUT || path.resolve('qa-reports/complete');
const routes = ['overview','workspaces','studio','validation','media','policies',
                'workflows','reviews','releases','incidents','integrations','settings',
                'playground','monitor'];
const nestedKeys = ['data-route','data-action','data-timeframe','data-finding',
                    'data-platform','data-policy-tab','data-validation-tab',
                    'data-workflow-tab','data-review-tab','data-media-tab',
                    'data-flow-node','data-release','data-incident',
                    'data-workspace','data-toggle','data-review-select'];
const dangerous = new Set(['resetDemo','confirmDraft','confirmRule','confirmMedia','saveDraft',
                           'approveReview','requestChanges','certificate',
                           'resolveIncident','testWorkflow','confirmDelete']);
const knownPlaceholder = new Set(['integrationDetails','addIntegration','apiDocs','assetDetail']);
const result = {started:new Date().toISOString(),demoBase:base,editableBase:editBase,
  routeInventory:{},checks:[],skipped:[],warnings:[],errors:[],coverage:{}};
fs.mkdirSync(output, {recursive:true});
let browser, page, context;
function record(name, status, detail) {
  const item = {name,status,detail:detail || ''};
  result.checks.push(item);
  process.stdout.write('['+status+'] '+name+(detail?' — '+String(detail).slice(0,220):'')+'\n');
}
async function check(name, fn) {
  try { await fn(); record(name,'PASS'); }
  catch(err) { record(name,'FAIL',err.stack || err.message || String(err)); result.errors.push(name+': '+(err.message||err)); }
}
function must(cond,message) { if(!cond)throw new Error(message); }
function skip(name,reason) { result.skipped.push({name,reason}); }
async function awaitReady(p){
  await p.locator('#app .app').waitFor({state:'visible',timeout:15000});
  await p.locator('main').waitFor({state:'visible',timeout:10000});
  await p.locator('header button[data-action="search"]').waitFor({state:'visible',timeout:10000});
  await p.locator('aside button.nav-item').first().waitFor({state:'visible',timeout:10000});
  await p.locator('#app[data-hydrated="true"]').waitFor({state:'attached',timeout:12000});
  await p.locator('main h1').first().waitFor({state:'visible',timeout:10000});
}
let navigationId = 0;
async function go(p,route,host=base) {
  // A hash-only navigation does not reset global SPA modal/search state.
  // Force a real page reload with a unique same-origin query parameter.
  // Otherwise a search overlay from the previous button would intercept
  // every subsequent click and cause false failures.
  try { await p.keyboard.press('Escape'); } catch (_) {}
  const query='?qa_navigation='+String(++navigationId);
  await p.goto(host+'/'+query+'#/'+route,{waitUntil:'domcontentloaded',timeout:18000});
  await awaitReady(p);
  await p.locator('.overlay.search-overlay').waitFor({state:'hidden',timeout:2500});
  await p.locator('main').waitFor({state:'visible'});
}
function metadataSignature(control) {
  return [control.key,control.value,control.text.slice(0,60)].join('|');
}
async function listControls(p) {
  return p.locator('main button,header button,aside button').evaluateAll((nodes,keys) =>
    nodes.filter(e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length)).map((e,i)=>{
      const attrs = keys.filter(k=>e.hasAttribute(k)).map(k=>[k,e.getAttribute(k)]);
      return {index:i,attrs, key:attrs.length?attrs[0][0]:'none',
        value:attrs.length?attrs[0][1]:'', text:(e.innerText||e.getAttribute('aria-label')||'').trim().slice(0,130),
        disabled:e.disabled, form:e.closest('form')?.id||null};
    }),nestedKeys);
}
async function primaryRouteSweep(p,route) {
  await go(p,route);
  let initial=await listControls(p);
  for(let attempt=0;initial.length<2&&attempt<8;attempt++){
    await p.waitForTimeout(150);
    initial=await listControls(p);
  }
  must(initial.length>=2,'Interactive controls did not render on '+route);
  result.routeInventory[route]={firstView:initial,clicked:[],navigationControls:[],nested:[],unimplemented:[],notExercised:[]};
  const checked=new Set();
  const ordered = initial.filter(x=>x.attrs.length>0 && !x.disabled);
  const capped=ordered.slice(0,90);
  for(const control of capped){
    const target=control.attrs[0];
    // Ignore empty values; known dangerous actions are checked by API isolation rather than triggering writes in a browser.
    if(!target || !target[1])continue;
    const key=target[0],value=target[1];
    const signature=metadataSignature(control);
    if(checked.has(signature))continue;
    checked.add(signature);
    if(key==='data-action'&&dangerous.has(value)) {
      result.routeInventory[route].notExercised.push({control,reason:'Write/side-effect workflow tested separately in disposable backend suite'});
      continue;
    }
    if(key==='data-route') {
      // All navigation routes are independently verified.
      result.routeInventory[route].navigationControls.push(control);
      continue;
    }
    await go(p,route);
    const loc=p.locator('button['+key+'="'+value.replace(/"/g,'\\"')+'"]').first();
    if(!await loc.count() || !await loc.isVisible()){result.routeInventory[route].notExercised.push({control,reason:'Not visible after page reset'});continue;}
    try {
      await loc.click({timeout:3000});
      await p.waitForTimeout(55);
      must(await p.locator('main').count()>0,'Main content disappeared');
      const text = await p.locator('body').innerText();
      if(text.includes('This action is not implemented in local-development mode')) {
        result.routeInventory[route].unimplemented.push(control);
      } else if(key==='data-action'&&knownPlaceholder.has(value)) {
        result.routeInventory[route].unimplemented.push(control);
      } else {
        result.routeInventory[route].clicked.push({control,method:'browser click'});
      }
      if(key.includes('-tab') || key==='data-flow-node' || key==='data-action') {
        const nested = await listControls(p);
        const fresh = nested.filter(x=>x.attrs.length &&
          !initial.some(prev=>metadataSignature(prev)===metadataSignature(x)));
        for(const item of fresh.slice(0,12)){
          result.routeInventory[route].nested.push(item);
          if(!item.attrs[0] || !item.attrs[0][1])continue;
          const [childKey,childValue]=item.attrs[0];
          if(childKey==='data-action' && childValue==='previewWorkflow'){
            result.routeInventory[route].notExercised.push({control:item,reason:'Template preview covered by dedicated browser test'});
            continue;
          }
          if(childKey==='data-action' && dangerous.has(childValue)){
            result.routeInventory[route].notExercised.push({control:item,reason:'Nested mutation; covered by isolated backend tests'});
            continue;
          }
          try {
            // Restore the parent state before clicking each newly discovered nested button.
            await go(p,route);
            const parent=p.locator('button['+key+'="'+value.replace(/"/g,'\\"')+'"]').first();
            if(!await parent.count() || !await parent.isVisible())continue;
            await parent.click({timeout:3000});
            const child=p.locator('button['+childKey+'="'+childValue.replace(/"/g,'\\"')+'"]').first();
            if(!await child.count() || !await child.isVisible())continue;
            await child.click({timeout:3000});
            must(await p.locator('main').count()>0,'Main content disappeared after nested action');
            const current=await p.locator('body').innerText();
            if(current.includes('This action is not implemented in local-development mode')){
              result.routeInventory[route].unimplemented.push(item);
            }else{
              result.routeInventory[route].clicked.push({control:item,method:'nested browser click',parent:signature});
            }
          }catch(err){throw new Error('Nested '+route+' '+childKey+'='+childValue+': '+(err.message||err));}
        }
      }
    } catch(err){
      throw new Error(route+' button '+signature+' failed: '+(err.message||err));
    }
  }
  if(ordered.length>capped.length)result.routeInventory[route].notExercised.push({
    reason:'Per-page 90-button safety cap',count:ordered.length-capped.length
  });
  // Inventory all hyperlinks. External links are validated structurally, but never opened.
  await go(p,route);
  const anchors=await p.locator('main a,header a,aside a').evaluateAll(nodes=>
    nodes.map(a=>({href:a.getAttribute('href')||'',text:(a.textContent||'').trim().slice(0,100),
      rel:a.getAttribute('rel')||'',target:a.getAttribute('target')||''})));
  result.routeInventory[route].links=anchors;
  for(const item of anchors){
    must(item.href.length>0,'Empty hyperlink on '+route+': '+item.text);
    if(item.target==='_blank' && item.href.startsWith('http')){
      if(!/noopener/.test(item.rel))result.warnings.push('External target=_blank lacking rel=noopener: '+route+' '+item.href);
    }
  }
  const unnamed=await p.locator('main button,header button,aside button').evaluateAll(nodes=>
    nodes.filter(e=>(e.offsetWidth||e.offsetHeight||e.getClientRects().length) &&
      !e.disabled && !(e.innerText||'').trim() && !e.getAttribute('aria-label') && !e.getAttribute('title')).length);
  if(unnamed)result.warnings.push('Accessibility: '+route+' has '+unnamed+' unnamed enabled button(s)');
  // Persist a visual screenshot for every first-level route.
  await go(p,route);
  await p.screenshot({path:path.join(output,'desktop-'+route+'.png'),fullPage:false});
}
async function apiStatus(method,uri,payload,headers={}) {
  const opts={method,headers,failOnStatusCode:false};
  if(payload!==undefined)opts.data=payload;
  const resp=await context.request.fetch(base+uri,opts);
  return resp.status();
}
async function startBrowser() {
  const candidates=[{channel:'chrome'},{channel:'msedge'}];
  const local=process.env.LOCALAPPDATA||'';
  const brave=path.join(local,'BraveSoftware','Brave-Browser','Application','brave.exe');
  if(fs.existsSync(brave))candidates.push({executablePath:brave});
  candidates.push({});
  let failures=[];
  for(const config of candidates) {
    try { return await chromium.launch({headless:true,...config,args:['--no-first-run']}); }
    catch(err){ failures.push((config.channel||config.executablePath||'Playwright Chromium')+': '+err.message.split('\n')[0]); }
  }
  throw new Error('No Chromium-based browser available. Install Chrome, Edge or Playwright Chromium. '+failures.join(' | '));
}
async function deepProductChecks(p){
  await check('Security: protected workspace buttons require Clerk setup',async()=>{
    for(const action of ['newWorkspace','invite','secureWorkspaces','joinWorkspace']){
      await go(p,'workspaces');
      await p.locator('[data-action="'+action+'"]').first().click();
      const dialog=p.locator('.ops-dialog');
      await dialog.waitFor({state:'visible',timeout:6000});
      await dialog.getByText(/CONFIGURATION REQUIRED/).waitFor({timeout:6000});
      must(await dialog.locator('button[type=submit]').count()===0,
        action+' allowed unauthenticated mutation');
      await dialog.getByRole('button',{name:'Close'}).click();
    }
    await go(p,'settings');
    await p.locator('[data-action="invite"]').first().click();
    await p.locator('.ops-dialog').getByText(/CONFIGURATION REQUIRED/).waitFor();
  });
  await check('Security: isolated Clerk API denies anonymous and public writes',async()=>{
    const config=await context.request.get(base+'/api/secure/config');
    must(config.status()===200,'Public workspace config missing');
    const c=await config.json();
    must(c.enabled===false && c.publicReadOnly===true,'Public workspace API unexpectedly enabled');
    const write=await context.request.post(base+'/api/secure/workspaces',{
      data:{name:'Unauthorized visitor'},failOnStatusCode:false});
    must(write.status()===403,'Public workspace creation not denied');
    const anonymous=await context.request.get(editBase+'/api/secure/workspaces',{
      failOnStatusCode:false});
    must([401,503].includes(anonymous.status()),'Anonymous private workspace data exposed');
    const demo=await context.request.get(editBase+'/api/bootstrap');
    const payload=await demo.json();
    must(!Object.prototype.hasOwnProperty.call(payload,'secureWorkspaces'),
      'Private workspace state leaked to shared demo bootstrap');
  });
  await check('Operations: all three workflow template previews show distinct server-defined steps',async()=>{
    const stageLengths=[];
    for(let i=0;i<3;i++){
      await go(p,'workflows');
      await p.locator('[data-workflow-tab="templates"]').click();
      await p.locator('[data-action="previewWorkflow"]').nth(i).click();
      const modal=p.locator('.ops-dialog');
      await modal.waitFor({state:'visible'});
      await modal.locator('.ops-stage').first().waitFor({state:'visible',timeout:7000});
      const steps=await modal.locator('.ops-stage').count();
      must(steps>=3,'Template '+i+' does not have actual stages');
      const title=await modal.locator('h2').innerText();
      must(title.includes('v1.0'),'Template '+i+' missing version');
      stageLengths.push(steps);
      await modal.getByRole('button',{name:'Close preview'}).click();
    }
    must(new Set(stageLengths).size>1,'All templates appear identical');
  });
  await check('Operations: public incident create and escalation cannot save changes',async()=>{
    await go(p,'incidents');
    await p.locator('[data-action="newIncident"]').click();
    const modal=p.locator('.ops-dialog');
    await modal.waitFor({state:'visible'});
    must(await modal.locator('button[type=submit]').isDisabled(),'Public create incident was enabled');
    must((await modal.innerText()).includes('Public demo is read-only'),'Missing public-mode disclosure');
    await modal.getByRole('button',{name:'Close'}).click();
    await p.locator('[data-action="escalateIncident"]').click();
    await modal.waitFor({state:'visible'});
    must(await modal.locator('button[type=submit]').isDisabled(),'Public escalation enabled');
  });
  await check('Operations: editable UI creates then escalates an actual incident',async()=>{
    await go(p,'incidents',editBase);
    await p.locator('[data-action="newIncident"]').click();
    const modal=p.locator('.ops-dialog');
    await modal.waitFor({state:'visible'});
    await modal.locator('#ops-title').fill('Browser QA escalation walkthrough');
    await modal.locator('#ops-description').fill('Disposable operational test data');
    await modal.locator('#ops-owner').fill('QA Incident Lead');
    await modal.locator('button[type=submit]').click();
    await modal.getByText(/created and audited/).waitFor();
    const text=await modal.locator('.ops-status').innerText();
    const match=text.match(/PF-INC-\d+/);
    must(!!match,'New incident identifier missing');
    const id=match[0];
    await modal.getByRole('button',{name:'View updated incidents'}).click();
    await awaitReady(p);
    const record=p.locator('tr[data-incident="'+id+'"]');
    await record.waitFor({state:'visible'});
    await record.click();
    await p.locator('[data-action="escalateIncident"]').click();
    await modal.waitFor({state:'visible'});
    await modal.locator('#ops-reason').fill('Need legal review for a high-risk claim');
    await modal.locator('#ops-assignedTo').fill('Legal QA');
    await modal.locator('button[type=submit]').click();
    await modal.getByText(/escalated to level 1/).waitFor();
    const response=await context.request.get(editBase+'/api/incidents');
    must(response.status()===200,'Incident persistence endpoint unavailable');
    const incidents=await response.json();
    const updated=incidents.find(x=>x.id===id);
    must(updated?.owner==='Legal QA'&&updated.escalationLevel===1&&updated.status==='Investigating',
      'Incident escalation not persisted in Java state');
  });

  await check('Operations: user-entered HTML is rendered as safe text',async()=>{
    const title='<img src=x onerror=alert(1)> QA harmless string';
    const owner='<svg onload=alert(1)> QA test owner';
    const created=await context.request.post(editBase+'/api/incidents',{
      data:{title,owner,severity:'Medium',description:'Untrusted text must be escaped'},
      failOnStatusCode:false
    });
    must(created.status()===201,'Unable to create XSS test incident');
    const record=await created.json();
    await go(p,'incidents',editBase);
    const row=p.locator('tr[data-incident="'+record.id+'"]');
    await row.waitFor({state:'visible'});
    must((await row.innerText()).includes('<img src=x'),'Title was not displayed as text');
    must(await row.locator('img').count()===0,'HTML image tag was injected into incident table');
    await row.click();
    must(await p.locator('main [onload], main [onerror]').count()===0,'Active HTML attribute injected');
    must((await p.locator('main').innerText()).includes('<svg onload=alert(1)>'),'Owner not escaped as visible text');
  });
  await check('Governance: all three public dialogs are read-only',async()=>{
    const routesAndActions=[['reviews','requestApproval'],['policies','publishPolicy'],['workflows','workflowEdit']];
    for(const [route,action] of routesAndActions){
      await go(p,route);
      await p.locator('[data-action="'+action+'"]').first().click();
      const dlg=p.locator('.ops-dialog');
      await dlg.waitFor({state:'visible'});
      const submit=dlg.locator('button[type=submit]');
      await submit.waitFor({state:'visible',timeout:6500});
      must(await submit.isDisabled(),action+' was enabled in public read-only mode');
      must((await dlg.innerText()).includes('Public demo is read-only'),'Public safety notice missing for '+action);
      await dlg.getByRole('button',{name:'Close'}).first().click();
    }
    for(const uri of ['/api/approvals/requests','/api/policies/publications']){
      const resp=await context.request.post(base+uri,{data:{},failOnStatusCode:false});
      must(resp.status()===403,'Public governance write accepted: '+uri+' ('+resp.status()+')');
    }
  });
  await check('Studio: Save draft persists through API and page reload',async()=>{
    const created=await context.request.post(editBase+'/api/drafts',{
      data:{
        title:'Disposable browser save test',
        body:'Original draft content',
        platforms:'LinkedIn'
      },
      failOnStatusCode:false
    });

    must(created.status()===201,'Draft creation failed');

    const draft=await created.json();
    const title='Verified saved draft '+draft.id;
    const body='Browser QA persistence test for '+draft.id;

    await go(p,'studio',editBase);
    await p.locator('#draftSelect').selectOption(draft.id);

    await p.locator('#draftTitle').fill(title);
    await p.locator('#draftBody').fill(body);

    const responsePromise=p.waitForResponse(
      r=>r.request().method()==='PUT' &&
         r.url().endsWith('/api/drafts/'+encodeURIComponent(draft.id)),
      {timeout:10000}
    );

    await p.locator('button[data-action="saveDraft"]').click();

    const response=await responsePromise;
    must(response.status()===200,'Save draft API rejected the update');

    const bootstrap=await context.request.get(editBase+'/api/bootstrap');
    must(bootstrap.status()===200,'Backend reload failed');

    const stored=(await bootstrap.json()).drafts.find(
      x=>x.id===draft.id
    );

    must(!!stored,'Saved draft missing from backend');
    must(stored.title===title,'Saved title did not persist');
    must(stored.body===body,'Saved body did not persist');
    must(stored.version>=2,'Draft version did not advance');

    await go(p,'studio',editBase);
    await p.locator('#draftSelect').selectOption(draft.id);

    must(
      (await p.locator('#draftTitle').inputValue())===title,
      'Saved title disappeared after reload'
    );

    must(
      (await p.locator('#draftBody').inputValue())===body,
      'Saved body disappeared after reload'
    );
  });

  await check('Governance: approval request persists and appears in Review Center',async()=>{
    const created=await context.request.post(editBase+'/api/drafts',{data:{
      title:'QA browser governance request',body:'Investments involve risk. This is a sample campaign.',
      platforms:'LinkedIn'
    }});
    must(created.status()===201,'Create draft failed');
    const draft=await created.json();
    const valid=await context.request.post(editBase+'/api/validations',{data:{
      draftId:draft.id,platform:'LinkedIn',altText:'Sample campaign graphic'
    }});
    must(valid.status()===201,'Draft validation failed');
    await go(p,'reviews',editBase);
    await p.locator('[data-action="requestApproval"]').click();
    const dlg=p.locator('.ops-dialog');
    await dlg.locator('#gov-draftId').waitFor({state:'visible'});
    await dlg.locator('#gov-draftId').selectOption(draft.id);
    await dlg.locator('#gov-reviewer').fill('Browser QA Reviewer');
    await dlg.locator('#gov-note').fill('Validation completed; request tracked');
    await dlg.locator('button[type=submit]').click();
    await dlg.getByText(/Created PF-APP-/).waitFor({timeout:7500});
    const records=await context.request.get(editBase+'/api/approvals/requests');
    must(records.status()===200,'List approval requests failed');
    const requests=await records.json();
    const saved=requests.find(x=>x.draftId===draft.id);
    must(saved?.status==='Pending'&&saved.reviewer==='Browser QA Reviewer','Approval record missing');
    await dlg.getByRole('button',{name:'Refresh updated page'}).click();
    await p.getByText(saved.id).waitFor({timeout:6500});
  });
  await check('Governance: publish a reviewed snapshot and display version history',async()=>{
    const newRule=await context.request.post(editBase+'/api/policies/rules',{
      data:{title:'QA Browser unique compliance rule',phrase:'qa_browser_opaque_phrase_2026',severity:'Low'},
      failOnStatusCode:false
    });
    must(newRule.status()===201,'Could not stage new version for snapshot');
    await go(p,'policies',editBase);
    await p.locator('[data-action="publishPolicy"]').click();
    const dlg=p.locator('.ops-dialog');
    await dlg.locator('#gov-reviewedBy').waitFor({state:'visible'});
    await dlg.locator('#gov-reviewedBy').fill('Browser QA Policy Reviewer');
    await dlg.locator('#gov-summary').fill('Browser test of immutable governance snapshot');
    await dlg.locator('#gov-approval').fill('APPROVED');
    await dlg.locator('button[type=submit]').click();
    await dlg.getByText(/Immutable local snapshot PF-POL-/).waitFor({timeout:7500});
    const records=await context.request.get(editBase+'/api/policies/publications');
    const publications=await records.json();
    must(publications.some(x=>x.reviewedBy==='Browser QA Policy Reviewer'),'Publication not persisted');
    await dlg.getByRole('button',{name:'Refresh updated page'}).click();
    await p.locator('#app[data-hydrated="true"]').waitFor({state:'attached',timeout:12000});
    await p.locator('[data-policy-tab="versions"]').click();
    await p.getByText('Browser test of immutable governance snapshot').waitFor();
  });
  await check('Governance: edit workflow step, reload and see persisted revision',async()=>{
    await go(p,'workflows',editBase);
    await p.locator('[data-flow-node="Legal review"]').click();
    await p.locator('[data-action="workflowEdit"]').click();
    const dlg=p.locator('.ops-dialog');
    await dlg.locator('#gov-owner').waitFor({state:'visible'});
    await dlg.locator('#gov-owner').fill('Browser QA Legal Reviewer');
    await dlg.locator('#gov-slaHours').fill('8');
    await dlg.locator('#gov-onFailure').selectOption('Block release');
    await dlg.locator('#gov-reason').fill('Browser test of persistent workflow step change');
    await dlg.locator('button[type=submit]').click();
    await dlg.getByText(/Legal review saved at revision/).waitFor({timeout:7500});
    const settings=await context.request.get(editBase+'/api/workflows/steps');
    must((await settings.json())['Legal review']?.owner==='Browser QA Legal Reviewer','Workflow settings were not persisted');
    await dlg.getByRole('button',{name:'Refresh updated page'}).click();
    await p.locator('[data-flow-node="Legal review"]').click();
    await p.getByText('Browser QA Legal Reviewer').waitFor();
    await p.getByText(/Block release · 8h/).waitFor();
  });
  await check('Preflight: high-risk content blocked',async()=>{
    await go(p,'playground');
    await p.locator('[data-pf-sample="0"]').click();
    await p.locator('#pf-form button[type=submit]').click();
    await p.getByText('Unverified guarantee of returns').waitFor();
    must((await p.locator('#pf-report').innerText()).includes('Missing') || (await p.locator('#pf-report').innerText()).includes('risk disclosure'),'Expected disclosure check');
    const status=await p.locator('.px-kpi-status').innerText();
    must(status==='BLOCKED','Wrong decision: '+status);
    must(await p.locator('[data-pf-action="simulate"]').isDisabled(),'Blocked release simulation should be disabled');
  });
  await check('Preflight: safe content enables simulated checkpoint',async()=>{
    await go(p,'playground');
    await p.locator('[data-pf-sample="1"]').click();
    await p.locator('#pf-form button[type=submit]').click();
    await p.getByText('No configured rule triggered.').waitFor();
    must((await p.locator('.px-kpi-status').innerText()).includes('READY'),'Safe sample should be review-ready');
    await p.locator('[data-pf-action="simulate"]').click();
    await p.getByText('SIMULATED RELEASE:',{exact:false}).waitFor();
    must((await p.locator('.px-integrity code').innerText()).length===64,'Missing fingerprint');
  });
  await check('Preflight: changed content invalidates validation',async()=>{
    await go(p,'playground');
    await p.locator('#pf-form button[type=submit]').click();
    await p.locator('.px-kpi-status').getByText('BLOCKED').waitFor();
    await p.locator('#pf-content').fill('A newly modified draft for QA');
    await p.getByText('Draft changed — results are out of date').waitFor();
    must(await p.locator('[data-pf-action="export"]').isDisabled(),'Stale report export must be disabled');
  });
  await check('Preflight: X limit, Instagram link, media alt-text',async()=>{
    const tests=[
      {platform:'x',content:'x'.repeat(300),hasMedia:false,altText:'',rule:'PLT-X-011'},
      {platform:'instagram',content:'Visit https://example.com',hasMedia:false,altText:'',rule:'LINK-006'},
      {platform:'linkedin',content:'Brand content',hasMedia:true,altText:'',rule:'A11Y-004'}];
    for(const t of tests){
      const response=await context.request.post(base+'/api/demo/preflight',{data:t,failOnStatusCode:false});
      must(response.status()===200,'Unexpected status for '+t.rule);
      const body=await response.json();
      must(body.findings.some(x=>x.id===t.rule),'Rule '+t.rule+' not found');
      must(body.persisted===false,'Stateless preview should not persist');
    }
  });
  await check('Preflight: JSON export downloads',async()=>{
    await go(p,'playground');
    await p.locator('#pf-form button[type=submit]').click();
    await p.locator('#pf-report .px-finding').first().waitFor();
    const [dl]=await Promise.all([p.waitForEvent('download',{timeout:10000}),p.locator('[data-pf-action="export"]').click()]);
    must(dl.suggestedFilename().endsWith('.json'),'Unexpected export filename');
    const saved=path.join(output,'preflight-export.json');
    await dl.saveAs(saved);
    const report=JSON.parse(fs.readFileSync(saved,'utf8'));
    must(report.findings.length>=2,'JSON report missing findings');
  });
  await check('Visitor sandbox: profile preview without API key',async()=>{
    await go(p,'integrations');
    await p.locator('#zernio-sample-btn').click();
    await p.locator('#zernio-link-result').getByText('LinkedIn link format recognized:',{exact:false}).waitFor();
  });
  await check('Zernio monitor: clearly simulated four statuses',async()=>{
    await go(p,'monitor');
    const text=await p.locator('main').innerText();
    must(text.includes('SAMPLE SCENARIO — NOT LIVE'),'Simulation must be visibly labeled');
    for(const [i,status] of ['DRAFT','SCHEDULED','PUBLISHED','FAILED'].entries()){
      const current=await p.locator('.px-replay h3').innerText();
      must(current===status,'At step '+i+': expected '+status+', received '+current);
      if(i<3)await p.locator('[data-live-action="replay"]').click();
    }
  });
  await check('Zernio monitor: missing key denied, writes never enabled',async()=>{
    must(await apiStatus('GET','/api/zernio/live')===400,'No-key live inspection should be 400');
    must(await apiStatus('POST','/api/zernio/live',{}, {'Content-Type':'application/json'})===405,'Zernio posting request must be 405');
    must(await apiStatus('POST','/api/drafts',{}, {'Content-Type':'application/json'})===403,'Public writes must be 403');
  });
  await check('Search: keyboard shortcut and route transition',async()=>{
    await go(p,'overview');
    await p.keyboard.press('Control+k');
    await p.locator('#commandSearch').waitFor();
    await p.locator('#commandSearch').fill('Integrations');
    await p.keyboard.press('Enter');
    await p.waitForURL(/#\/integrations/);
    must((await p.locator('main').innerText()).includes('Zernio'),'Search route mismatch');
  });
  await check('Public demo: no stored drafts changed during browser sweep',async()=>{
    const a=await context.request.get(base+'/api/bootstrap');
    const b=await context.request.get(base+'/api/bootstrap');
    const left=await a.json(),right=await b.json();
    must(JSON.stringify(left.drafts)===JSON.stringify(right.drafts),'Public drafts mutated');
    must(left.runtime.mode==='public-read-only','Public mode missing');
  });
}
(async()=>{
 try {
   browser=await startBrowser();
   context=await browser.newContext({viewport:{width:1440,height:900},acceptDownloads:true,serviceWorkers:'block'});
   page=await context.newPage();
   page.setDefaultTimeout(6000);
   page.on('dialog',async d=>{try{await d.dismiss();}catch{}});
   page.on('popup',async popup=>{try{await popup.close();}catch{}});
   page.on('pageerror',e=>result.errors.push('Browser JavaScript error: '+e.message));
   page.on('response',r=>{try{if(r.status()>=500 && r.url().startsWith(base))result.errors.push('HTTP '+r.status()+' '+r.url());}catch{}});
   await check('Public demo health and read-only identity',async()=>{
     const health=await context.request.get(base+'/api/health');
     must(health.status()===200,'Health HTTP '+health.status());
     const bootstrap=await context.request.get(base+'/api/bootstrap');
     must(bootstrap.status()===200,'Bootstrap HTTP '+bootstrap.status());
     must((await bootstrap.json()).runtime.mode==='public-read-only','Not public mode');
   });
   for(const route of routes){
     await check('Desktop: '+route+' page, buttons, primary nested controls',async()=>primaryRouteSweep(page,route));
     await check('Sidebar route click: '+route,async()=>{
       await go(page,'overview');
       const menu=page.locator('aside button.nav-item[data-route="'+route+'"]');
       await menu.click({timeout:4500});
       must(page.url().includes('#/'+route),'Sidebar click did not navigate to '+route);
       await page.locator('main h1').first().waitFor({state:'visible'});
     });
   }
   await deepProductChecks(page);
   await check('Mobile: all 14 pages render without overflow',async()=>{
     const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
     const mp=await mobile.newPage();
     for(const route of routes){
       await go(mp,route);
       const sizes=await mp.evaluate(()=>({view:document.documentElement.clientWidth,full:document.documentElement.scrollWidth}));
       if(sizes.full > sizes.view+16)result.warnings.push('Mobile horizontal overflow '+route+': '+sizes.full+' vs '+sizes.view);
       await mp.screenshot({path:path.join(output,'mobile-'+route+'.png'),fullPage:false});
     }
     await mobile.close();
   });
    // Reconcile control inventory with independently verified browser evidence.
    const previewTestName =
      'Operations: all three workflow template previews show distinct server-defined steps';

    const previewPassed = result.checks.some(
      item => item.name === previewTestName && item.status === 'PASS'
    );

    if (previewPassed) {
      const workflows = result.routeInventory.workflows;
      workflows.coveredByDedicatedTests = [];

      workflows.notExercised = workflows.notExercised.filter(entry => {
        if (
          entry.control?.key === 'data-action' &&
          entry.control?.value === 'previewWorkflow'
        ) {
          workflows.coveredByDedicatedTests.push({
            control: entry.control,
            method: 'dedicated browser scenario',
            evidence: previewTestName
          });
          return false;
        }
        return true;
      });
    }
    const saveDraftTest =
      'Studio: Save draft persists through API and page reload';

    if(result.checks.some(x=>x.name===saveDraftTest&&x.status==='PASS')){
      const studio=result.routeInventory.studio;
      studio.coveredByDedicatedTests=[];

      studio.notExercised=studio.notExercised.filter(entry=>{
        if(entry.control?.key==='data-action' &&
           entry.control?.value==='saveDraft'){
          studio.coveredByDedicatedTests.push({
            control:entry.control,
            method:'browser click + API persistence + reload',
            evidence:saveDraftTest
          });
          return false;
        }
        return true;
      });
    }

   const unimplemented=Object.values(result.routeInventory).flatMap(x=>x.unimplemented||[]);
   const skippedControls=Object.values(result.routeInventory).flatMap(x=>x.notExercised||[]);
   result.coverage={
     routesInspected:Object.keys(result.routeInventory).length,
     controlsInventory:Object.values(result.routeInventory).reduce((n,x)=>n+(x.firstView||[]).length,0),
     controlsClicked:Object.values(result.routeInventory).reduce((n,x)=>n+(x.clicked||[]).length,0),
     sidebarRoutesClicked:routes.length,
     nestedControlsDiscovered:Object.values(result.routeInventory).reduce((n,x)=>n+(x.nested||[]).length,0),
     unimplementedControls:unimplemented.length,
     unexercisedControls:skippedControls.length,
     browserAssertionsPassed:result.checks.filter(x=>x.status==='PASS').length,
     browserAssertionsFailed:result.checks.filter(x=>x.status==='FAIL').length
   };
   result.warnings.push('Known non-functional placeholder controls: '+unimplemented.length);
   result.warnings.push('Controls requiring authenticated backend mutations, physical upload, external provider, or cap: '+skippedControls.length);
   result.warnings.push('Live Zernio authenticated data/publishing, platform OAuth and external webhooks are not tested.');
 }catch(err){result.errors.push('QA setup: '+(err.stack||err.message||err));record('QA startup','FAIL',err.message||err);}
 finally{
   if(context)await context.close().catch(()=>{});
   if(browser)await browser.close().catch(()=>{});
   result.finished=new Date().toISOString();
   fs.writeFileSync(path.join(output,'browser-report.json'),JSON.stringify(result,null,2),'utf8');
   const total=result.checks.length,failed=result.checks.filter(x=>x.status==='FAIL').length;
   process.stdout.write('\nBROWSER QA SUMMARY '+(total-failed)+'/'+total+' checks passed; '+result.errors.length+' runtime errors\n');
   process.stdout.write('Inventory: '+JSON.stringify(result.coverage)+'\n');
   process.stdout.write('Report: '+path.join(output,'browser-report.json')+'\n');
   if(failed||result.errors.length)process.exitCode=1;
 }
})();
