const fs = require('fs'); const vm=require('vm');
const base=process.argv[2]||'http://127.0.0.1:8098';
let code=fs.readFileSync(__dirname+'/../web/assets/app.js','utf8');
code=code.replace(/\}\)\(\);\s*$/, `globalThis.PF_TEST={getState:()=>state,route,serverAction,flushDraftSave,currentDraft,render,ui};})();`);
const elems=new Map(),host={innerHTML:''},toasts={appendChild:()=>{}};
const document={getElementById(id){if(id==='app')return host;if(id==='toasts')return toasts;if(id==='modalDraftTitle')return {value:'VM tested campaign'};return elems.get(id)||null;},addEventListener(){},createElement(){return {remove(){},click(){},setAttribute(){}};}};
const window={scrollTo(){},addEventListener(){}};
const location={hash:'#/overview'};
const ctx={document,window,location,localStorage:{getItem(){return null},setItem(){},removeItem(){}},fetch:(path,opts)=>fetch(base+path,opts),console,structuredClone,URL,Blob,confirm:()=>true,navigator:{clipboard:{writeText:async()=>{}}},setTimeout:(fn,n)=>setTimeout(fn,Math.min(n,30)),clearTimeout};
vm.createContext(ctx);vm.runInContext(code,ctx);
const assert=require('assert');const wait=(ms=90)=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 await wait();const test=ctx.PF_TEST;assert(test,'Need test hook');assert(test.getState().runtime?.persistent,'API bootstrap must hydrate server state');
 console.log('✓ Frontend bootstrapped from persisted Java API');
 const routes=['overview','workspaces','studio','validation','media','policies','workflows','reviews','releases','incidents','integrations','settings'];
 for(const route of routes){test.route(route);assert(host.innerHTML.length>1000,route+' empty');assert(host.innerHTML.includes('page-title'),route+' heading missing');}
 console.log('✓ All 12 frontend routes render via JavaScript without DOM runtime errors');
 await test.serverAction('confirmDraft');const id=test.getState().selectedDraft;assert(id.startsWith('PF-'));assert(test.getState().drafts[0].title==='VM tested campaign');
 const d=test.currentDraft();d.body='Guaranteed returns on investments!';d.altText='Visualized financial support campaign';test.getState().altText=d.altText;
 await test.flushDraftSave();await test.serverAction('runPreflight');assert(test.getState().findings.some(f=>f.id==='FIN-MKT-001'));
 d.body='Take control of everyday budgeting and spending with simple habits.';await test.flushDraftSave();await test.serverAction('runPreflight');assert(test.getState().findings.length===0,'Clean post should pass');
 await test.serverAction('approveReview');assert(test.getState().reviewStatus[id]==='Approved');await test.serverAction('certificate');assert(test.getState().releasesIssued[0].draftId===id);
 test.route('releases');assert(host.innerHTML.includes(test.getState().releasesIssued[0].id));
 console.log('✓ Draft creation → edit → failed validation → remediation → approval → certificate flow works');
 console.log('PASS — State-connected frontend VM smoke tests');
})().catch(e=>{console.error(e);process.exitCode=1});
