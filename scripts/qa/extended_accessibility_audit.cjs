'use strict';
// Accessibility test for disposable read-only localhost demo. No Clerk or remote posting.
const fs=require('fs');
const path=require('path');
const {chromium}=require('playwright');
const axePath=require.resolve('axe-core/axe.min.js');
const root=process.env.PF_QA_OUTPUT;
const url=process.env.PF_QA_DEMO;
const routes=['overview','workspaces','studio','validation','media','policies','workflows','reviews','releases','incidents','integrations','settings','playground','monitor'];
const report={schemaVersion:1,scope:'disposable-public-ui',checks:[],violations:[],errors:[],manualReview:['screen reader semantics and announcements','usability of keyboard-only tasks','visual design across actual devices','authenticated Clerk dialogs and role-specific UI']};
const assert=(condition,message)=>{if(!condition)throw Error(message);};
async function run(){
  const u=new URL(url||'');
  assert((u.hostname==='localhost'||u.hostname==='127.0.0.1')&&u.port==='10003'&&u.protocol==='http:'&&u.pathname==='/',
    'Accessibility refuses a non-disposable QA origin');
  assert(root && path.isAbsolute(root),'PF_QA_OUTPUT must be absolute');
  fs.mkdirSync(root,{recursive:true});
  const configs=[{channel:'chrome'},{channel:'msedge'}];
  const local=process.env.LOCALAPPDATA||'';
  const brave=path.join(local,'BraveSoftware','Brave-Browser','Application','brave.exe');
  if(fs.existsSync(brave))configs.push({executablePath:brave});
  configs.push({});
  let browser,errors=[];
  for(const config of configs){
    try{browser=await chromium.launch({headless:true,...config,args:['--no-first-run']});break;}
    catch(e){errors.push(String(e.message||e).split('\n')[0]);}
  }
  assert(!!browser,'No browser available: '+errors.join(' | '));
  try{
    const ctx=await browser.newContext({viewport:{width:1440,height:900},reducedMotion:'reduce',bypassCSP:true});
    const p=await ctx.newPage();
    p.on('pageerror',e=>report.errors.push('PAGE_ERROR '+e.message));
    for(const [index,route] of routes.entries()){
      const item={name:'axe '+route,status:'PASS',violationCount:0};
      try{
        await p.goto(u.origin+'/?extended_qa='+index+'#/'+route,{waitUntil:'domcontentloaded',timeout:16000});
        await p.locator('#app[data-hydrated="true"]').waitFor({timeout:12000});
        await p.addScriptTag({path:axePath});
        const result=await p.evaluate(async()=>window.axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21a','wcag21aa']}}));
        item.violationCount=result.violations.length;
        for(const v of result.violations){
          report.violations.push({route,id:v.id,impact:v.impact,description:v.description,nodes:v.nodes.map(n=>({target:n.target,summary:n.failureSummary})).slice(0,20)});
        }
        if(result.violations.some(v=>['critical','serious'].includes(v.impact))){
          item.status='FAIL';
          item.detail='Serious/critical automated WCAG violation; see extended-accessibility.json';
        }
      }catch(e){item.status='FAIL';item.detail=String(e.message||e);report.errors.push(route+': '+item.detail);}
      report.checks.push(item);
      process.stdout.write('['+item.status+'] '+item.name+' violations='+item.violationCount+'\n');
    }
    await ctx.close();
  }finally{await browser.close();}
}
(async()=>{
  try{await run();}catch(e){report.errors.push('SETUP '+String(e.stack||e));report.checks.push({name:'Accessibility setup',status:'FAIL',detail:String(e.message||e)});}
  finally{
    if(root&&path.isAbsolute(root)){try{fs.mkdirSync(root,{recursive:true});fs.writeFileSync(path.join(root,'extended-accessibility.json'),JSON.stringify(report,null,2),'utf8');}catch(e){report.errors.push('REPORT '+e.message);}}
    const failed=report.checks.filter(x=>x.status==='FAIL').length;
    process.stdout.write('ACCESSIBILITY: '+(report.checks.length-failed)+' PASS / '+failed+' FAIL; '+report.violations.length+' findings\n');
    if(failed||report.errors.length)process.exitCode=1;
  }
})();
