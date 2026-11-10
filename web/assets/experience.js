(()=>{
'use strict';
const encode = x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const samples=[
  {title:'High-risk investment claim',platform:'linkedin',content:'Invest now! Guaranteed 20% returns and instant profits. Contact advice@demo.example today.',alt:'',media:false},
  {title:'Safer financial education',platform:'linkedin',content:'Financial confidence begins with informed decisions. Explore practical ways to plan, save and understand investment risks. Investments involve risk. Past performance does not guarantee future results.',alt:'',media:false},
  {title:'Instagram link + accessibility review',platform:'instagram',content:'Discover our new educational campaign: https://example.com/learn #Finance #Investing',alt:'',media:true}
];
const pf={content:samples[0].content,platform:'linkedin',altText:'',hasMedia:false,report:null,loading:false,error:'',simulation:null};
const live={key:null,data:null,loading:false,error:'',auto:false,consent:false,replay:0};
const when=s=>{if(!s)return 'Not available';const d=new Date(s);return Number.isNaN(d.getTime())?encode(s):encode(d.toLocaleString());};
const badged=(t,cls='neutral')=>`<span class="px-badge ${cls}">${encode(t)}</span>`;
const sec=(title,subtitle)=>`<div class="px-sectionhead"><h2>${title}</h2><p>${subtitle}</p></div>`;
const failure=(s)=>`<div class="px-error" role="alert">${encode(s)}</div>`;
function preflightPage(){
 const r=pf.report, summary=r?.summary||{}, status=r?.status||'NOT EVALUATED';
 return `<div class="px-wrap">
 <div class="px-hero"><div class="px-eyebrow">INTERACTIVE WORKSPACE / JAVA 17 POLICY ENGINE</div>
 <div class="px-hero-row"><div><h1>Content Preflight Lab</h1><p>Check a real draft against explainable financial-marketing, platform and accessibility rules. No account required; your content is processed transiently, never saved.</p></div>
 ${badged('STATELESS LIVE CHECK','good')}</div>
 <div class="px-kpis"><div><span>Policy version</span><strong>${encode(r?.policyVersion||'FIN-MKT-v3.4')}</strong></div><div><span>Risk score</span><strong>${r?encode(r.score)+'/100':'—'}</strong></div><div><span>Blocking findings</span><strong>${r?(Number(summary.critical||0)+Number(summary.high||0)):'—'}</strong></div><div><span>Decision</span><strong class="px-kpi-status">${encode(status.replaceAll('_',' '))}</strong></div></div></div>
 <div class="px-two">
 <section class="px-panel"><div class="px-panel-head"><div><h2>1 · Draft content</h2><p>Edit any text or load a sample scenario.</p></div>${badged('NO LOGIN','good')}</div>
 <div class="px-samples">${samples.map((s,i)=>`<button type="button" class="px-sample" data-pf-sample="${i}">${encode(s.title)}</button>`).join('')}</div>
 <form id="pf-form" autocomplete="off">
 <label for="pf-content">Post copy</label><textarea id="pf-content" rows="9" maxlength="4000" required spellcheck="true">${encode(pf.content)}</textarea>
 <div class="px-counter" id="pf-counter">${Array.from(pf.content).length} / 4,000 characters</div>
 <div class="px-formrow"><div><label for="pf-platform">Target platform</label><select id="pf-platform">${['linkedin','instagram','x','facebook','threads','tiktok','youtube'].map(s=>`<option value="${s}" ${pf.platform===s?'selected':''}>${s==='x'?'X':s[0].toUpperCase()+s.slice(1)}</option>`).join('')}</select></div>
 <div class="px-mediaswitch"><label for="pf-media"><input type="checkbox" id="pf-media" ${pf.hasMedia?'checked':''}> Includes an image</label></div></div>
 <label for="pf-alt">Image alternative text (if applicable)</label><input id="pf-alt" maxlength="500" value="${encode(pf.altText)}" placeholder="Describe the key visual content">
 <div class="px-actions"><button class="px-primary" type="submit" ${pf.loading?'disabled':''}>${pf.loading?'Analyzing…':'Run real Java preflight'}</button><span>Server checks · No database writes · No Zernio required</span></div>
 </form></section>
 <section class="px-panel" id="pf-report">${renderPreflightReport()}</section>
 </div>
 ${sec('2 · Release integrity preview','The content fingerprint locks a proposed release to exactly the text and platform you evaluated. A simulated approval is not a real authorization.')}
 <div class="px-panel">
 <div class="px-integrity"><div><span>SHA-256 content fingerprint</span><code>${r?encode(r.fingerprint):'Run a check to generate a fingerprint'}</code></div><div><span>Evidence status</span><strong>${pf.simulation?'Simulation generated · not legally binding':'No approval recorded'}</strong></div></div>
 ${pf.simulation?`<div class="px-pass">SIMULATED RELEASE: Version fingerprint ${encode(pf.simulation.hash.slice(0,16))}… · ${encode(pf.simulation.when)} · NOT PUBLISHED / NOT APPROVED</div>`:''}
 <div class="px-actions"><button class="px-outline" type="button" data-pf-action="simulate" ${!r||r.status==='BLOCKED'?'disabled':''}>Simulate release checkpoint</button>
 <button class="px-outline" type="button" data-pf-action="export" ${!r?'disabled':''}>Export findings JSON</button></div>
 </div>
 <p class="px-footnote">Demo preview only. The heuristic rules are not a substitute for legal review or Zernio's official publishing validation. Neither draft text nor results are persisted in PostFlight's application database.</p>
 </div>`;
}
function renderPreflightReport(){
 const r=pf.report;
 if(pf.loading) return '<div class="px-placeholder"><span class="px-loader"></span><h3>Running deterministic checks</h3><p>Evaluating policy, content and platform requirements…</p></div>';
 if(pf.error) return failure(pf.error);
 if(!r) return '<div class="px-placeholder"><div class="px-big-icon">✓</div><h3>Your validation report will appear here</h3><p>Choose a sample or write your own post, then select <strong>Run real Java preflight</strong>. You will receive actual findings with actionable explanations.</p></div>';
 const findings=Array.isArray(r.findings)?r.findings:[];
 return `<div class="px-panel-head"><div><h2>Preflight findings</h2><p>Evaluated ${when(r.checkedAt)} · ${encode(r.characterCount)} characters · ${encode(r.wordCount)} words</p></div>${badged(r.status,r.status==='BLOCKED'?'bad':findings.length?'warn':'good')}</div>
 <div class="px-score"><div><span>Quality signal</span><strong>${encode(r.score)}<small>/100</small></strong></div><div><span>Findings</span><strong>${findings.length}</strong></div><div><span>Human review</span><strong>Required</strong></div></div>
 ${findings.length?findings.map(f=>`<div class="px-finding"><div class="px-finding-top">${badged(f.severity,f.severity==='Critical'?'bad':f.severity==='High'?'warn':'neutral')}<strong>${encode(f.title)}</strong></div><p>${encode(f.detail)}</p><div class="px-fix"><span>Recommended action</span>${encode(f.fix)}</div><small>${encode(f.id)} · ${encode(f.channel)}</small></div>`).join(''):'<div class="px-pass"><strong>No configured rule triggered.</strong> This is not approval or proof of legal compliance. A human should still review the content.</div>'}
 <p class="px-footnote">${encode(r.disclaimer)}</p>`;
}
const demoEvents=[
 {status:'draft',detail:'A content version is created in Content Studio',time:'Step 1 / 4'},
 {status:'scheduled',detail:'The approved draft is queued for a connected destination',time:'Step 2 / 4'},
 {status:'published',detail:'Zernio confirms a successful platform delivery',time:'Step 3 / 4'},
 {status:'failed',detail:'Another destination rejects delivery; investigate before retrying',time:'Step 4 / 4'}
];
function livePage(){
 const data=live.data, verified=!!data, posts=Array.isArray(data?.posts)?data.posts:[],
       accounts=Array.isArray(data?.accounts)?data.accounts:[], health=data?.accountHealth||{};
 return `<div class="px-wrap">
 <div class="px-hero"><div class="px-eyebrow">ZERNIO CONNECTOR / DELIVERY INTELLIGENCE</div><div class="px-hero-row">
 <div><h1>Live Delivery Monitor</h1><p>See your own connected-account health and actual scheduled, published or failed Zernio posts. Unlike a profile URL preview, this makes live read-only API calls.</p></div>
 ${badged(verified?'VERIFIED ZERNIO SNAPSHOT':'SAMPLE SCENARIO — NOT LIVE',verified?'good':'warn')}</div>
 <div class="px-kpis"><div><span>Connected accounts</span><strong>${verified?encode(health.total??accounts.length):'—'}</strong></div>
 <div><span>Healthy accounts</span><strong>${verified?encode(health.healthy??'—'):'—'}</strong></div>
 <div><span>Need reconnection</span><strong>${verified?encode(health.needsReconnect??'—'):'—'}</strong></div>
 <div><span>Posts returned</span><strong>${verified?posts.length:'—'}</strong></div></div></div>
 <section class="px-panel"><div class="px-panel-head"><div><h2>1 · Connect a read-only data source</h2><p>Use a restricted, profile-scoped key from your own Zernio workspace. A full-access publishing key is not appropriate here.</p></div>${badged('NO POSTING ENDPOINT','good')}</div>
 ${live.key?'<div class="px-pass">Read-only credentials are held in this browser tab’s memory for refreshes. They are not saved in localStorage or the PostFlight database. Click Disconnect to clear them.</div>':''}
 <form id="pf-live-form" autocomplete="off">
 <label for="pf-live-key">Your restricted Zernio API key</label>
 <div class="px-keyrow"><input type="password" id="pf-live-key" maxlength="512" placeholder="${live.key?'Key in tab memory — enter to replace':'Paste a read-only key, not your social password'}" autocomplete="off" ${live.key?'':'required'}>
 <button class="px-primary" type="submit" ${live.loading?'disabled':''}>${live.loading?'Fetching…':live.key?'Refresh actual posts':'Load actual posts'}</button></div>
 <label class="px-consent"><input type="checkbox" id="pf-live-consent" required ${live.consent?'checked':''}> I understand my key will pass transiently through PostFlight hosting and Zernio, and remain only in this browser tab’s memory until I disconnect or close the tab. I will use a restricted read-only key.</label>
 </form>
 <div class="px-actions">
 <button class="px-outline" type="button" data-live-action="refresh" ${!live.key||live.loading?'disabled':''}>Refresh now</button>
 <button class="px-outline" type="button" data-live-action="disconnect" ${!live.key?'disabled':''}>Disconnect and clear key</button>
 <label class="px-consent"><input type="checkbox" id="pf-live-auto" ${live.auto?'checked':''} ${!live.key?'disabled':''}> Refresh every 60 seconds while this page is open</label>
 </div>
 ${live.error?failure(live.error):''}
 <div class="px-footnote">No visitor can see another visitor’s account data unless they possess that visitor’s valid credentials. This is a read-only lookup, not social OAuth onboarding. Zernio and infrastructure services may process requests. Data refreshes on request; it is not a streaming feed.</div></section>
 ${sec('2 · Account connection health',verified?'Actual health reported by the Zernio API at '+when(data.checkedAt):'No authorized source connected. Sample data is never labeled live.')}
 <section class="px-panel">
 ${verified?(accounts.length?accounts.map(a=>`<div class="px-event"><div>${badged(a.status||'unknown',a.status==='healthy'?'good':'warn')} <strong>${encode(a.platform||'Account')} · ${encode(a.username||a.accountId||'Connected profile')}</strong></div>
 <p>Posting scope reported: ${a.canPost===true?'Yes':a.canPost===false?'No':'Unknown'} · Reconnection needed: ${a.needsReconnect===true?'Yes':'No / not reported'}</p></div>`).join(''):'<div class="px-empty">No connected account health rows were returned. Connect a social account in your own Zernio dashboard before expecting real activity.</div>'):`<div class="px-empty"><strong>Not connected.</strong> You can still explore the sample lifecycle below, but it is not Zernio activity.</div>`}
 </section>
 ${sec('3 · Recent post lifecycle',verified?'Actual recent Zernio posts (latest 10 returned by the API)':'A transparent walkthrough of statuses you could monitor after connecting an account.')}
 <section class="px-panel">
 ${verified?(posts.length?posts.map(p=>`<div class="px-event"><div class="px-event-top">${badged(p.status||'unknown',p.status==='published'?'good':p.status==='failed'||p.status==='partial'?'bad':'warn')}
 <span>${encode(p.title||((p.content||'').slice(0,110)||'Post without text'))}</span></div>
 <p>Scheduled: ${when(p.scheduledFor)} · Published: ${when(p.publishedAt)}</p>
 <div class="px-targets">${(p.platforms||[]).map(a=>`<span>${encode(a.platform||'Platform')}: ${encode(a.status||'unknown')}</span>`).join('')}</div>
 </div>`).join(''):'<div class="px-empty">This Zernio key currently has no recent posts. The API is connected, but there is no activity to show yet.</div>'):
 `<div class="px-replay"><div class="px-replay-status">${badged('SIMULATED EVENT','neutral')} ${encode(demoEvents[live.replay].time)}</div>
 <h3>${encode(demoEvents[live.replay].status.toUpperCase())}</h3><p>${encode(demoEvents[live.replay].detail)}</p>
 <button type="button" class="px-outline" data-live-action="replay">Next sample event →</button>
 <p class="px-footnote">Demonstration sequence only. No Zernio request or social publishing occurs.</p></div>`}
 </section>
 <div class="px-panel px-next" style="margin-top:16px"><strong>Want actual activity?</strong>
 <p>Connect one of your social accounts through your private <a href="https://zernio.com/dashboard" rel="noopener noreferrer" target="_blank">Zernio workspace</a>, create a restricted read-only key, then click <strong>Load actual posts</strong>. Only connected accounts and authorized Zernio posts can appear as live data. Actual post metrics require the appropriate provider features and permissions.</p></div>
 </div>`;
}
function rerender(){window.dispatchEvent(new Event('hashchange'));}
async function runPreflight(){
 pf.error='';pf.loading=true;pf.report=null;pf.simulation=null;rerender();
 try{
   const response=await fetch('/api/demo/preflight',{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},
      body:JSON.stringify({content:pf.content,platform:pf.platform,altText:pf.altText,hasMedia:pf.hasMedia}),
      credentials:'omit',cache:'no-store'});
   const payload=await response.json();
   if(!response.ok)throw new Error(payload.error||'Preflight request failed: '+response.status);
   pf.report=payload;
 }catch(err){pf.error=err.message||'Validation failed.';}
 finally{pf.loading=false;rerender();}
}
async function runLive(){
 if(!live.key)return;
 live.loading=true;live.error='';rerender();
 try{
  const response=await fetch('/api/zernio/live',{method:'GET',headers:{Authorization:'Bearer '+live.key,Accept:'application/json'},
    credentials:'omit',cache:'no-store',redirect:'error'});
  const payload=await response.json();
  if(!response.ok)throw new Error(payload.error||'Zernio request failed: '+response.status);
  live.data=payload;
 }catch(err){live.error=err.message||'Unable to retrieve Zernio activity.';live.data=null;}
 finally{live.loading=false;rerender();}
}
document.addEventListener('input',e=>{
  const v=e.target;
  if(v.id==='pf-content'){
    pf.content=v.value;pf.report=null;pf.simulation=null;
    const counter=document.getElementById('pf-counter');
    if(counter)counter.textContent=Array.from(v.value).length+' / 4,000 characters';
  }
  if(v.id==='pf-alt'){pf.altText=v.value;pf.report=null;pf.simulation=null;}
});
document.addEventListener('change',e=>{
  const v=e.target;
  if(v.id==='pf-platform'){pf.platform=v.value;pf.report=null;pf.simulation=null;}
  if(v.id==='pf-media'){pf.hasMedia=v.checked;pf.report=null;pf.simulation=null;}
  if(v.id==='pf-live-consent')live.consent=v.checked;
  if(v.id==='pf-live-auto')live.auto=v.checked;
});
document.addEventListener('submit',e=>{
 if(e.target.id==='pf-form'){e.preventDefault();void runPreflight();}
 if(e.target.id==='pf-live-form'){
   e.preventDefault();
   if(!live.consent){live.error='Please acknowledge the privacy notice before connecting.';rerender();return;}
   const input=e.target.querySelector('#pf-live-key');
   if(input.value.trim())live.key=input.value.trim();
   input.value='';
   void runLive();
 }
});
document.addEventListener('click',e=>{
 const button=e.target.closest('[data-pf-sample],[data-pf-action],[data-live-action]');
 if(!button)return;
 if(button.dataset.pfSample!==undefined){
   const sample=samples[Number(button.dataset.pfSample)];
   if(!sample)return;
   Object.assign(pf,{content:sample.content,platform:sample.platform,altText:sample.alt,hasMedia:sample.media,report:null,error:'',simulation:null});
   rerender();
 }
 if(button.dataset.pfAction==='simulate' && pf.report && pf.report.status!=='BLOCKED'){
   pf.simulation={hash:pf.report.fingerprint,when:new Date().toISOString()};
   rerender();
 }
 if(button.dataset.pfAction==='export'&&pf.report){
   const blob=new Blob([JSON.stringify(pf.report,null,2)],{type:'application/json'});
   const href=URL.createObjectURL(blob);
   const a=document.createElement('a');a.href=href;a.download='postflight-preflight-preview.json';a.click();
   setTimeout(()=>URL.revokeObjectURL(href),1000);
 }
 if(button.dataset.liveAction==='refresh'&&live.key)void runLive();
 if(button.dataset.liveAction==='disconnect'){
   live.key=null;live.data=null;live.auto=false;live.consent=false;live.error='Session cleared from application memory.';rerender();
 }
 if(button.dataset.liveAction==='replay'){live.replay=(live.replay+1)%demoEvents.length;rerender();}
});
// A refresh is opt-in; polling occurs only while the authorized monitor is visible.
setInterval(()=>{if(live.auto&&live.key&&!live.loading&&location.hash.startsWith('#/monitor')&&!document.hidden)void runLive();},60000);
window.addEventListener('pagehide',()=>{live.key=null;live.data=null;});
window.PostFlightExperience={preflightPage,livePage};
})();
