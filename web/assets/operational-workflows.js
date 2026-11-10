(() => {
  'use strict';
  // Real incident controls and read-only template previews. Kept separate from
  // app.js so the existing dashboard/router can be upgraded safely.
  const actions = new Set(['newIncident','escalateIncident','previewWorkflow']);
  let dialog = null, busy = false;
  const elem=(name,cls,text)=>{const el=document.createElement(name);if(cls)el.className=cls;if(text!==undefined)el.textContent=String(text);return el;};
  function field(label,type,opts={}) {
    const group=elem('div','ops-field');
    const l=elem('label','',label); const el=document.createElement(type==='select'?'select':type==='textarea'?'textarea':'input');
    if(type!=='select'&&type!=='textarea')el.type=type||'text';
    el.name=opts.name||label.toLowerCase().replaceAll(' ','_');el.id='ops-'+el.name;
    l.htmlFor=el.id;
    el.required=!!opts.required;
    if(opts.maxLength)el.maxLength=opts.maxLength;
    if(opts.placeholder)el.placeholder=opts.placeholder;
    if(opts.value)el.value=opts.value;
    if(type==='select')for(const option of opts.options||[]) {const o=elem('option','',option);o.value=option;el.append(o);}
    group.append(l,el);return {group,input:el};
  }
  function close(){if(!dialog)return;document.removeEventListener('keydown',escapeKey,true);dialog.remove();dialog=null;busy=false;}
  function escapeKey(e){if(e.key==='Escape'&&!busy){e.preventDefault();e.stopPropagation();close();}}
  function open(title,subtitle){
    close();
    dialog=elem('div','ops-backdrop');
    const card=elem('section','ops-dialog');card.setAttribute('role','dialog');card.setAttribute('aria-modal','true');card.setAttribute('aria-label',title);
    const head=elem('div','ops-head');const intro=elem('div','');
    intro.append(elem('h2','',title),elem('p','',subtitle));
    const x=elem('button','ops-close','×');x.type='button';x.setAttribute('aria-label','Close');x.addEventListener('click',close);
    head.append(intro,x);card.append(head);dialog.append(card);
    dialog.addEventListener('click',e=>{if(e.target===dialog&&!busy)close();});
    document.body.append(dialog);document.addEventListener('keydown',escapeKey,true);
    x.focus();return card;
  }
  function notice(target,text,kind='info'){
    const n=elem('div','ops-notice '+kind,text);n.setAttribute('role','status');target.append(n);return n;
  }
  function footer(form,label,disabled) {
    const actions=elem('div','ops-buttons');
    const cancel=elem('button','ops-secondary','Cancel');cancel.type='button';cancel.addEventListener('click',close);
    const submit=elem('button','ops-primary',label);submit.type='submit';submit.disabled=disabled;
    actions.append(cancel,submit);form.append(actions);return submit;
  }
  async function getMode(){
    const res=await fetch('/api/bootstrap',{headers:{Accept:'application/json'},cache:'no-store',credentials:'omit'});
    if(!res.ok)throw new Error('Backend unavailable: HTTP '+res.status);
    const body=await res.json();return body.runtime?.mode||'unknown';
  }
  async function send(path,payload){
    const response=await fetch(path,{method:'POST',headers:{Accept:'application/json','Content-Type':'application/json'},
      body:JSON.stringify(payload),credentials:'same-origin',cache:'no-store'});
    const result=await response.json().catch(()=>({error:'Unexpected server response'}));
    if(!response.ok)throw new Error(result.error||'Request failed (HTTP '+response.status+')');
    return result;
  }
  function done(result,message){
    const box=dialog?.querySelector('.ops-status');if(box){box.textContent=message;box.className='ops-status ops-success';}
    busy=false;
    const submit=dialog?.querySelector('button[type=submit]');if(submit){submit.disabled=true;submit.textContent='Saved';}
    const link=elem('button','ops-primary','View updated incidents');
    link.addEventListener('click',()=>{close();window.location.hash='#/incidents';window.location.reload();});
    dialog?.querySelector('.ops-buttons')?.append(link);
  }
  async function beginMutation(title,subtitle,configure){
    const card=open(title,subtitle);
    let mode;
    try{mode=await getMode();}catch(e){notice(card,e.message,'error');return;}
    if(!dialog||!card.isConnected)return;
    const publicMode=mode!=='local-development';
    if(publicMode)notice(card,'Public demo is read-only. This form previews the workflow but cannot save changes. Use the local private Java runtime for incident management.');
    else notice(card,'Changes are persisted to local PostFlight storage and recorded in the audit trail. Do not use this unauthenticated mode on a public network.');
    const form=elem('form','ops-form');
    const status=elem('div','ops-status','');status.setAttribute('role','alert');
    configure(form,publicMode,status);
    card.append(form,status);
  }
  async function createIncident(){
    await beginMutation('Create incident','Register a risk event with an accountable owner and a persistent audit trail.',(form,readOnly,status)=>{
      const title=field('Incident title','text',{name:'title',required:true,maxLength:140,placeholder:'Unapproved investment claim'});
      const desc=field('Description','textarea',{name:'description',maxLength:2000,placeholder:'Describe what happened and the supporting evidence'});
      const severity=field('Severity','select',{name:'severity',options:['Critical','High','Medium','Low']});
      const owner=field('Owner','text',{name:'owner',value:'Compliance lead',maxLength:100});
      form.append(title.group,desc.group,severity.group,owner.group);
      const submit=footer(form,'Create incident',readOnly);
      form.addEventListener('submit',async e=>{
        e.preventDefault();if(busy||readOnly)return;busy=true;submit.disabled=true;status.textContent='Saving incident…';
        try{
          const created=await send('/api/incidents',{title:title.input.value,description:desc.input.value,
            severity:severity.input.value,owner:owner.input.value});
          done(created,'Incident '+created.id+' created and audited. Status: Open.');
        }catch(err){busy=false;submit.disabled=false;status.textContent=err.message;status.className='ops-status ops-error';}
      });
    });
  }
  function selectedIncidentId(){
    const selected=document.querySelector('tr[data-incident].selected');
    if(selected)return selected.getAttribute('data-incident');
    const header=document.querySelector('main .split .card:last-child .card-title h3, main .split .card:last-child h3');
    const match=(header?.textContent||'').match(/PF-(?:INC-|ALERT-)?[0-9]+/);
    return match?.[0]||document.querySelector('tr[data-incident]')?.getAttribute('data-incident')||null;
  }
  async function escalateIncident(){
    const id=selectedIncidentId();
    if(!id){const card=open('Escalate incident','Choose an incident from the register first.');notice(card,'No incident selected.','error');return;}
    await beginMutation('Escalate '+id,'Record the escalation reason, new owner and incident status transition.',(form,readOnly,status)=>{
      const reason=field('Escalation reason','textarea',{name:'reason',required:true,maxLength:500,
        placeholder:'Explain why this incident needs increased attention'});
      const owner=field('Assign to','text',{name:'assignedTo',required:true,maxLength:100,value:'Compliance lead'});
      form.append(reason.group,owner.group);
      const submit=footer(form,'Escalate incident',readOnly);
      form.addEventListener('submit',async e=>{
        e.preventDefault();if(busy||readOnly)return;busy=true;submit.disabled=true;status.textContent='Escalating…';
        try{
          const result=await send('/api/incidents/'+encodeURIComponent(id)+'/escalate',
            {reason:reason.input.value,assignedTo:owner.input.value});
          done(result,'Incident '+id+' escalated to level '+result.escalationLevel+
               ', assigned to '+result.owner+' and moved to Investigating.');
        }catch(err){busy=false;submit.disabled=false;status.textContent=err.message;status.className='ops-status ops-error';}
      });
    });
  }
  async function previewWorkflow(button){
    const title=button.closest('.card')?.querySelector('h3')?.textContent?.trim()||'';
    const card=open('Workflow template preview','Server-defined stages, owners and expected response times. No workflow is started or published.');
    try{
      const response=await fetch('/api/workflows/templates',{headers:{Accept:'application/json'},
        cache:'no-store',credentials:'omit'});
      if(!response.ok)throw new Error('Templates unavailable (HTTP '+response.status+')');
      const templates=await response.json();
      if(!Array.isArray(templates))throw new Error('Unexpected workflow response');
      const spec=templates.find(t=>t.name===title);
      if(!spec)throw new Error('Template not found: '+title);
      card.querySelector('h2').textContent=spec.name+' · v'+spec.version;
      notice(card,spec.purpose);
      const rows=elem('ol','ops-stages');
      for(const [idx,step] of (spec.stages||[]).entries()){
        const item=elem('li','ops-stage');
        const heading=elem('strong','',String(idx+1)+'. '+step.name);
        const meta=elem('p','',step.owner+' · Expected SLA: '+step.slaHours+'h'+(step.required?' · Required':' · Optional'));
        item.append(heading,meta);rows.append(item);
      }
      card.append(rows);
      const bar=elem('div','ops-buttons');
      const x=elem('button','ops-secondary','Close preview');x.type='button';x.addEventListener('click',close);bar.append(x);card.append(bar);
    }catch(e){notice(card,e.message,'error');}
  }
  document.addEventListener('click',event=>{
    const button=event.target.closest?.('[data-action]');
    if(!button||!actions.has(button.dataset.action))return;
    event.preventDefault();event.stopImmediatePropagation();
    if(button.dataset.action==='newIncident')void createIncident();
    if(button.dataset.action==='escalateIncident')void escalateIncident();
    if(button.dataset.action==='previewWorkflow')void previewWorkflow(button);
  },true);
})();