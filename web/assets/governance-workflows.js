(() => {
  'use strict';
  const supported=new Set(['requestApproval','publishPolicy','workflowEdit']);
  const make=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
  let panel=null,busy=false;
  const api=async(method,url,data)=>{
    const options={method,headers:{Accept:'application/json'},credentials:'same-origin',cache:'no-store'};
    if(data!==undefined){options.headers['Content-Type']='application/json';options.body=JSON.stringify(data);}
    const response=await fetch(url,options);
    const body=await response.json().catch(()=>({error:'Non-JSON backend response'}));
    if(!response.ok)throw new Error(body.error||'HTTP '+response.status);
    return body;
  };
  function dismiss(){if(panel&&!busy){panel.remove();panel=null;document.removeEventListener('keydown',onEscape,true);}}
  function onEscape(e){if(e.key==='Escape'){e.stopPropagation();e.preventDefault();dismiss();}}
  function open(title,description){
    if(panel)panel.remove();busy=false;
    panel=make('div',undefined,'ops-backdrop');
    const dialog=make('section',undefined,'ops-dialog');
    dialog.role='dialog';dialog.setAttribute('aria-modal','true');dialog.setAttribute('aria-label',title);
    const header=make('div',undefined,'ops-head'),titles=make('div');
    titles.append(make('h2',title),make('p',description));
    const x=make('button','×','ops-close');x.type='button';x.setAttribute('aria-label','Close');
    x.addEventListener('click',dismiss);header.append(titles,x);dialog.append(header);panel.append(dialog);
    panel.addEventListener('click',e=>{if(e.target===panel)dismiss();});
    document.body.append(panel);document.addEventListener('keydown',onEscape,true);
    x.focus();return dialog;
  }
  function msg(container,text,kind='info'){
    const el=make('div',text,kind==='error'?'ops-notice error':'ops-notice');
    el.setAttribute('role','status');container.append(el);return el;
  }
  function input(form,label,config={}){
    const wrapper=make('div',undefined,'ops-field'),labelEl=make('label',label);
    const el=document.createElement(config.kind==='select'?'select':config.kind==='textarea'?'textarea':'input');
    if(!config.kind||config.kind==='text'||config.kind==='number')el.type=config.kind||'text';
    el.id='gov-'+config.id;el.name=config.id;
    labelEl.htmlFor=el.id;
    if(config.required)el.required=true;
    if(config.maxLength)el.maxLength=config.maxLength;
    if(config.placeholder)el.placeholder=config.placeholder;
    if(config.value!==undefined)el.value=String(config.value);
    if(config.min!==undefined)el.min=config.min;
    if(config.max!==undefined)el.max=config.max;
    if(config.kind==='select')for(const value of config.options||[]){
      const option=make('option',value);option.value=value;el.append(option);
    }
    wrapper.append(labelEl,el);form.append(wrapper);
    return el;
  }
  function footer(form,title,disabled){
    const area=make('div',undefined,'ops-buttons'),cancel=make('button','Cancel','ops-secondary');
    cancel.type='button';cancel.addEventListener('click',dismiss);
    const submit=make('button',title,'ops-primary');submit.type='submit';submit.disabled=!!disabled;
    area.append(cancel,submit);form.append(area);return submit;
  }
  async function prepare(title,desc){
    const dialog=open(title,desc);
    try{
      const state=await api('GET','/api/bootstrap');
      if(!dialog.isConnected)return null;
      const disabled=state.runtime?.mode!=='local-development';
      msg(dialog,disabled?'Public demo is read-only. Inspect this form here; saving governance changes requires a private local runtime.':'Local demo records are not authenticated identities or regulatory approvals. Changes are persisted and audited only in your local Java runtime.');
      return {dialog,disabled,state};
    }catch(e){msg(dialog,e.message,'error');return null;}
  }
  async function submitForm(form,button,status,execute){
    if(busy)return;busy=true;button.disabled=true;status.textContent='Saving to local Java API…';
    try{
      const response=await execute();
      status.textContent=response.message;status.className='ops-status ops-success';
      button.textContent='Saved';form.querySelectorAll('input,textarea,select').forEach(x=>x.disabled=true);
      const view=make('button','Refresh updated page','ops-secondary');view.type='button';
      view.addEventListener('click',()=>{panel.remove();panel=null;window.location.reload();});
      form.querySelector('.ops-buttons')?.append(view);
    }catch(e){status.textContent=e.message;status.className='ops-status ops-error';button.disabled=false;}
    finally{busy=false;}
  }
  async function approval(){
    const ready=await prepare('Request approval','Choose a draft and reviewer label. Requests are bound to the exact validated draft version and current policy.');
    if(!ready)return;const {dialog,disabled,state}=ready;
    const form=make('form',undefined,'ops-form');
    const drafts=state.drafts||[];
    if(!drafts.length){msg(dialog,'No drafts found. Create a draft first.','error');return;}
    const draftRow=make('div',undefined,'ops-field'),draftLabel=make('label','Draft'),draftSelect=make('select');
    draftSelect.id='gov-draftId';draftSelect.className='ops-select';
    draftLabel.htmlFor=draftSelect.id;
    for(const draft of drafts){
      const opt=make('option',draft.id+' · '+draft.title+' · v'+(draft.version||1));opt.value=draft.id;
      draftSelect.append(opt);
    }
    if(state.selectedDraft&&drafts.some(d=>d.id===state.selectedDraft))draftSelect.value=state.selectedDraft;
    draftRow.append(draftLabel,draftSelect);form.append(draftRow);
    const reviewer=input(form,'Reviewer label',{id:'reviewer',required:true,maxLength:100,placeholder:'Local compliance reviewer'});
    const note=input(form,'Review note',{id:'note',kind:'textarea',maxLength:500,placeholder:'Why is this draft ready for review?'});
    const detail=msg(form,'Select a draft. The current version must have a non-blocked validation result.');
    const update=()=>{
      const d=drafts.find(x=>x.id===draftSelect.value);
      const run=(state.lastRuns||{})[draftSelect.value];
      detail.textContent=d?'Version '+(d.version||1)+' · '+(run?'Latest validation: '+run.verdict:'No stored validation; run preflight first'):'Draft not found';
    };
    draftSelect.addEventListener('change',update);update();
    const button=footer(form,'Request approval',disabled);const status=make('div',undefined,'ops-status');status.role='alert';form.append(status);
    form.addEventListener('submit',e=>{e.preventDefault();if(disabled)return;const d=drafts.find(x=>x.id===draftSelect.value);
      void submitForm(form,button,status,async()=>{
        const response=await api('POST','/api/approvals/requests',{
          draftId:d.id,expectedVersion:d.version||1,reviewer:reviewer.value,note:note.value
        });
        return {message:'Created '+response.id+' for '+response.draftId+' v'+response.draftVersion+'. Status: Pending. No email was sent.'};
      });
    });
    dialog.append(form);
  }
  async function publish(){
    const ready=await prepare('Publish local policy snapshot','Records immutable evidence for the current rules. This is not a regulatory certification or remote rules deployment.');
    if(!ready)return;const {dialog,disabled,state}=ready;
    const policies=await api('GET','/api/policies').catch(()=>null);
    if(!policies){msg(dialog,'Could not load current policy version.','error');return;}
    msg(dialog,'Current active demo policy: '+policies.version+'. Custom rules already take effect when created. Publishing records the reviewed state; it does not change that enforcement behavior.');
    const form=make('form',undefined,'ops-form');
    const reviewedBy=input(form,'Reviewer label',{id:'reviewedBy',required:true,maxLength:100,placeholder:'Local policy reviewer'});
    const summary=input(form,'Change summary',{id:'summary',kind:'textarea',required:true,maxLength:500,placeholder:'Why this version is approved for the local demo'});
    const approval=input(form,'Confirmation',{id:'approval',required:true,maxLength:20,placeholder:'Type APPROVED'});
    msg(form,'Explicit confirmation must be the exact word APPROVED.');
    const button=footer(form,'Record policy publication',disabled),status=make('div',undefined,'ops-status');status.role='alert';form.append(status);
    form.addEventListener('submit',e=>{e.preventDefault();if(disabled)return;
      void submitForm(form,button,status,async()=>{
        const response=await api('POST','/api/policies/publications',{
          expectedVersion:policies.version,reviewedBy:reviewedBy.value,summary:summary.value,approval:approval.value
        });
        return {message:'Immutable local snapshot '+response.id+' created for '+response.version+'. Content hash: '+response.contentHash.slice(0,16)+'…'};
      });
    });
    dialog.append(form);
  }
  async function editStep(){
    const selected=document.querySelector('.flow-node.selected');
    const name=selected?.querySelector('b')?.textContent?.replace(/^\d+\s*/, '').trim();
    const known=['Content validation','Risk classification','Legal review','Brand review','Release approval'];
    const step=known.find(x=>x.toUpperCase()===name?.toUpperCase())||'Legal review';
    const ready=await prepare('Edit workflow step · '+step,'Change the local step configuration with optimistic concurrency and an audit trail. No live execution is modified.');
    if(!ready)return;const {dialog,disabled}=ready;
    const settings=await api('GET','/api/workflows/steps').catch(()=>({}));
    const previous=settings[step]||{},revision=previous.revision||0;
    msg(dialog,'Current revision: '+revision+'. A conflicting update will be rejected instead of overwriting another change.');
    const form=make('form',undefined,'ops-form');
    const owner=input(form,'Assigned reviewer',{id:'owner',required:true,maxLength:100,value:previous.owner||'Compliance reviewer'});
    const sla=input(form,'Response target (hours)',{id:'slaHours',kind:'number',required:true,min:1,max:168,value:previous.slaHours||24});
    const failure=input(form,'On rejection',{id:'onFailure',kind:'select',options:['Request changes','Escalate','Block release']});
    failure.value=previous.onFailure||'Request changes';
    const reason=input(form,'Reason for change',{id:'reason',kind:'textarea',required:true,maxLength:500,placeholder:'Explain why this workflow configuration needs revision'});
    const button=footer(form,'Save step revision',disabled),status=make('div',undefined,'ops-status');status.role='alert';form.append(status);
    form.addEventListener('submit',e=>{e.preventDefault();if(disabled)return;
      void submitForm(form,button,status,async()=>{
        const response=await api('PUT','/api/workflows/steps/'+encodeURIComponent(step),{
          expectedRevision:revision,owner:owner.value,slaHours:Number(sla.value),
          onFailure:failure.value,reason:reason.value
        });
        return {message:step+' saved at revision '+response.revision+'. Reload the page to view its persisted settings.'};
      });
    });
    dialog.append(form);
  }
  document.addEventListener('click',event=>{
    const button=event.target.closest?.('[data-action]');
    if(!button||!supported.has(button.dataset.action))return;
    event.preventDefault();event.stopImmediatePropagation();
    if(button.dataset.action==='requestApproval')void approval();
    if(button.dataset.action==='publishPolicy')void publish();
    if(button.dataset.action==='workflowEdit')void editStep();
  },true);
})();