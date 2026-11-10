(() => {
  'use strict';
  // Clerk owns the session and refreshes tokens. PostFlight never persists JWTs.
  let config=null,overlay=null,busy=false,clerk=null,pendingKind=null;
  const managed=new Set(['newWorkspace','invite','secureWorkspaces','joinWorkspace']);
  const el=(tag,content,cls)=>{
    const n=document.createElement(tag);if(content!==undefined)n.textContent=content;
    if(cls)n.className=cls;return n;
  };
  async function json(method,path,body){
    const headers={Accept:'application/json'};
    const token=await clerk?.session?.getToken();
    if(token)headers.Authorization='Bearer '+token;
    if(body!==undefined)headers['Content-Type']='application/json';
    const res=await fetch(path,{method,headers,credentials:'omit',cache:'no-store',
      body:body===undefined?undefined:JSON.stringify(body)});
    const data=await res.json().catch(()=>({error:'Unexpected server response'}));
    if(!res.ok)throw new Error(data.error||'Request failed (HTTP '+res.status+')');
    return data;
  }
  function close(){if(busy)return;if(overlay)overlay.remove();overlay=null;document.removeEventListener('keydown',escKey,true);}
  function escKey(e){if(e.key==='Escape'){e.preventDefault();e.stopPropagation();close();}}
  function modal(title,description){
    if(overlay)overlay.remove();busy=false;
    overlay=el('div',undefined,'ops-backdrop');
    const card=el('section',undefined,'ops-dialog');
    card.role='dialog';card.setAttribute('aria-modal','true');card.setAttribute('aria-label',title);
    const header=el('div',undefined,'ops-head'),intro=el('div');
    intro.append(el('h2',title),el('p',description));
    const x=el('button','×','ops-close');x.type='button';x.setAttribute('aria-label','Close');x.addEventListener('click',close);
    header.append(intro,x);card.append(header);overlay.append(card);document.body.append(overlay);
    overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
    document.removeEventListener('keydown',escKey,true);document.addEventListener('keydown',escKey,true);
    x.focus();return card;
  }
  function info(parent,text,type){
    const box=el('div',text,'ops-notice'+(type==='error'?' error':''));
    box.role='status';parent.append(box);return box;
  }
  function button(parent,text,handler,primary=false){
    const b=el('button',text,primary?'ops-primary':'ops-secondary');
    b.type='button';b.addEventListener('click',()=>void handler());parent.append(b);return b;
  }
  function row(parent,label,config={}){
    const box=el('div',undefined,'ops-field'),lab=el('label',label);
    const node=document.createElement(config.options?'select':'input');
    if(!config.options)node.type=config.type||'text';
    node.id='ws-'+(config.name||label.replace(/\W+/g,'-'));
    node.required=!!config.required;node.maxLength=config.maxLength||120;
    if(config.placeholder)node.placeholder=config.placeholder;
    lab.htmlFor=node.id;
    for(const option of config.options||[]){
      const o=el('option',option.label||option.value);o.value=option.value;node.append(o);
    }
    box.append(lab,node);parent.append(box);return node;
  }
  function canOperate(){return !!(clerk?.isSignedIn && clerk?.session);}
  function startLogin(kind){
    if(!config?.enabled||!clerk){
      info(overlay?.querySelector('.ops-dialog')||document.body,
        'Clerk is not configured. Set POSTFLIGHT_CLERK_PUBLISHABLE_KEY, POSTFLIGHT_CLERK_ISSUER and POSTFLIGHT_APP_ORIGIN.',
        'error');return;
    }
    pendingKind=kind;
    clerk.openSignIn();
  }
  async function boot(){
    try {
      config=await json('GET','/api/secure/config');
      if(!config.enabled)return;
      clerk=await window.PostFlightClerk.initialize(config);
      clerk.addListener(({session})=>{
        if(session && pendingKind){
          const kind=pendingKind;
          pendingKind=null;
          void openPage(kind);
        }
      }, {skipInitialEmit:true});
    } catch(e) {
      const card=modal('Clerk sign-in','Authentication setup could not finish.');
      info(card,e.message,'error');
      clerk=null;
    }
  }
  function gate(card,kind){
    if(!config?.enabled){
      info(card,'CONFIGURATION REQUIRED — secure workspaces are disabled. Sample organizations are illustrative only.');
      info(card,'Configure POSTFLIGHT_CLERK_PUBLISHABLE_KEY, POSTFLIGHT_CLERK_ISSUER and POSTFLIGHT_APP_ORIGIN on a private runtime.');
      return true;
    }
    if(!clerk){
      info(card,'Clerk is not ready. Refresh after checking your configuration and network connection.','error');
      return true;
    }
    if(!canOperate()){
      info(card,'Sign in with Clerk to access your private workspaces.');
      button(card,'Sign in / Sign up',()=>startLogin(kind),true);
      return true;
    }
    button(card,'Sign out',async()=>{await clerk.signOut();close();});
    return false;
  }
  async function showList(card){
    const list=await json('GET','/api/secure/workspaces');
    info(card,list.length?'Your isolated workspaces are shown below. These do not include the sample organizations.':'No authenticated workspaces yet. Create one to become its owner.');
    const items=el('div',undefined,'ops-stages');
    for(const w of list){
      const item=el('div',undefined,'ops-stage');
      item.append(el('strong',w.name+' · '+w.yourRole),el('p',w.memberCount+' member(s) · '+w.id));
      if(['OWNER','ADMIN'].includes(w.yourRole)){
        button(item,'Invite user',()=>void openPage('invite',w.id));
        button(item,'Members',async()=>{
          const members=await json('GET','/api/secure/workspaces/'+w.id+'/members');
          const panel=modal('Workspace members','Authenticated member roster for '+w.name);
          for(const m of members)info(panel,m.username+' · '+m.sub+' · '+m.role);
          button(panel,'Back to workspaces',()=>openPage('secureWorkspaces'));
        });
      }
      items.append(item);
    }
    card.append(items);
  }
  async function openPage(kind,selectedWorkspace){
    const title={newWorkspace:'New secure workspace',invite:'Invite workspace member',
      secureWorkspaces:'Secure workspaces',joinWorkspace:'Accept workspace invitation'}[kind]||'Secure workspaces';
    const card=modal(title,'Authenticated, tenant-scoped management. Existing shared demo content is not tenant data.');
    if(gate(card,kind))return;
    try{
      if(kind==='secureWorkspaces'){await showList(card);return;}
      const form=el('form',undefined,'ops-form');
      if(kind==='newWorkspace'){
        info(card,'Create a workspace as its OWNER. This does not clone existing demo drafts or grant public write access.');
        const name=row(form,'Workspace name',{name:'name',required:true,maxLength:100,placeholder:'Acme Content Operations'});
        form.addEventListener('submit',e=>{e.preventDefault();void save(form,async()=>{
          const created=await json('POST','/api/secure/workspaces',{name:name.value});
          return 'Created '+created.name+' · '+created.id+' (Owner)';
        });});
      }else if(kind==='invite'){
        const workspaces=(await json('GET','/api/secure/workspaces')).filter(w=>['OWNER','ADMIN'].includes(w.yourRole));
        if(!workspaces.length){info(card,'Only an OWNER or ADMIN can invite members.','error');return;}
        const ws=row(form,'Workspace',{name:'workspace',options:workspaces.map(w=>({label:w.name,value:w.id}))});
        if(selectedWorkspace)ws.value=selectedWorkspace;
        const sub=row(form,'Target Clerk user ID',{name:'sub',required:true,maxLength:100,
          placeholder:'user_... ID from Clerk Dashboard'});
        const role=row(form,'Role',{name:'role',options:['VIEWER','REVIEWER','ADMIN'].map(v=>({value:v,label:v}))});
        info(card,'Invitations are bound to an exact Clerk user ID (user_...), expire after 24 hours, and can be used once. No invitation email is sent.');
        form.addEventListener('submit',e=>{e.preventDefault();void save(form,async()=>{
          const result=await json('POST','/api/secure/workspaces/'+ws.value+'/invitations',{
            targetSub:sub.value,role:role.value
          });
          const safe=el('div',undefined,'ops-notice');
          safe.append(el('strong','Invitation ID: '+result.id),
            el('p','Share the invitation ID and one-time token securely with the intended user:'),
            el('code',result.inviteToken));
          card.append(safe);
          button(card,'Copy one-time invitation details',async()=>{
            try{await navigator.clipboard.writeText(result.id+'\n'+result.inviteToken);}
            catch{info(card,'Clipboard unavailable; copy the ID and token shown above.');}
          });
          return 'Invitation created. Token is shown only this once and is not emailed or stored in plaintext.';
        });});
      }else if(kind==='joinWorkspace'){
        const id=row(form,'Invitation ID',{name:'invId',required:true,maxLength:100,
          placeholder:'inv_...'});
        const token=row(form,'One-time token',{name:'token',required:true,maxLength:150,
          placeholder:'Token supplied by workspace admin'});
        form.addEventListener('submit',e=>{e.preventDefault();void save(form,async()=>{
          const w=await json('POST','/api/secure/invitations/accept',{
            invitationId:id.value,inviteToken:token.value
          });
          return 'Joined '+w.name+' with '+w.yourRole+' role.';
        });});
      }
      const controls=el('div',undefined,'ops-buttons');
      button(controls,'My workspaces',()=>openPage('secureWorkspaces'));
      const submit=el('button',kind==='newWorkspace'?'Create workspace':kind==='invite'?'Create invitation':'Accept invitation','ops-primary');
      submit.type='submit';controls.append(submit);
      form.append(controls);card.append(form);
    }catch(e){info(card,e.message,'error');}
  }
  async function save(form,work){
    if(busy)return;busy=true;
    const submit=form.querySelector('button[type=submit]');if(submit)submit.disabled=true;
    const card=overlay?.querySelector('.ops-dialog');
    try{
      const text=await work();
      info(card,text);
      for(const field of form.querySelectorAll('input,select'))field.disabled=true;
      if(submit)submit.textContent='Saved';
    }catch(e){info(card,e.message,'error');if(submit)submit.disabled=false;}
    finally{busy=false;}
  }
  document.addEventListener('click',e=>{
    const button=e.target.closest?.('[data-action]');
    if(!button||!managed.has(button.dataset.action))return;
    e.preventDefault();e.stopImmediatePropagation();
    void openPage(button.dataset.action);
  },true);
  window.addEventListener('pageshow',()=>void boot(),{once:true});
})();