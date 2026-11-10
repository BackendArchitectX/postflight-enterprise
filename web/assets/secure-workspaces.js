(() => {
  'use strict';
  // Cognito authorization-code + PKCE. Access token is held in RAM only:
  // NEVER in localStorage, sessionStorage, URL or browser logs.
  let accessToken=null,expiresAt=0,config=null,overlay=null,busy=false;
  const managed=new Set(['newWorkspace','invite','secureWorkspaces','joinWorkspace']);
  const el=(tag,content,cls)=>{
    const n=document.createElement(tag);if(content!==undefined)n.textContent=content;
    if(cls)n.className=cls;return n;
  };
  async function json(method,path,body){
    const headers={Accept:'application/json'};
    if(accessToken)headers.Authorization='Bearer '+accessToken;
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
  function canOperate(){return accessToken&&Date.now()<expiresAt-30000;}
  function random(bytes){
    const a=new Uint8Array(bytes);crypto.getRandomValues(a);
    return btoa(String.fromCharCode(...a)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
  }
  async function startLogin(kind){
    if(!config?.enabled||!config?.hostedDomain||!config?.clientId){
      info(overlay?.querySelector('.ops-dialog')||document.body,'Cognito sign-in is not configured. Set the user pool, app client and hosted domain on a private deployment.','error');return;
    }
    if(!window.isSecureContext||!crypto?.subtle){
      info(overlay.querySelector('.ops-dialog'),'A secure HTTPS or localhost origin is required for PKCE.','error');return;
    }
    const verifier=random(32),state=random(24);
    const challenge=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier));
    const encoded=btoa(String.fromCharCode(...new Uint8Array(challenge))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
    sessionStorage.setItem('postflight.oauth.pkce',JSON.stringify({
      verifier,state,kind,issuedAt:Date.now(),redirectUri:location.origin+'/'
    }));
    const q=new URLSearchParams({
      response_type:'code',client_id:config.clientId,redirect_uri:location.origin+'/',
      scope:'openid profile',state,code_challenge:encoded,code_challenge_method:'S256'
    });
    location.assign('https://'+config.hostedDomain+'/oauth2/authorize?'+q.toString());
  }
  async function callback(){
    const url=new URL(location.href),code=url.searchParams.get('code');
    const error=url.searchParams.get('error');
    if(!code&&!error)return;
    const saved=sessionStorage.getItem('postflight.oauth.pkce');
    sessionStorage.removeItem('postflight.oauth.pkce');
    history.replaceState(null,'',location.pathname+'#/workspaces');
    if(!saved)throw new Error('OAuth session not found; start sign-in again');
    const req=JSON.parse(saved);
    if(Date.now()-req.issuedAt>300000||req.redirectUri!==location.origin+'/'
      ||req.state!==url.searchParams.get('state'))throw new Error('OAuth state expired or mismatched');
    if(error)throw new Error('Cognito sign-in failed: '+error);
    if(!config?.enabled||!config?.hostedDomain)throw new Error('Cognito is not configured');
    const body=new URLSearchParams({
      grant_type:'authorization_code',client_id:config.clientId,
      redirect_uri:req.redirectUri,code,code_verifier:req.verifier
    });
    const res=await fetch('https://'+config.hostedDomain+'/oauth2/token',{
      method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
      credentials:'omit',cache:'no-store',body
    });
    if(!res.ok)throw new Error('Could not exchange authorization code with Cognito');
    const payload=await res.json();
    if(payload.token_type!=='Bearer'||typeof payload.access_token!=='string')
      throw new Error('Invalid Cognito token response');
    accessToken=payload.access_token;expiresAt=Date.now()+Math.min(Number(payload.expires_in||3600),86400)*1000;
    await json('GET','/api/secure/me');
    await openPage(req.kind||'secureWorkspaces');
  }
  async function boot(){
    try{
      config=await json('GET','/api/secure/config');
      await callback();
    }catch(e){
      const card=modal('Cognito sign-in','The identity flow did not complete.');
      info(card,e.message,'error');
      accessToken=null;expiresAt=0;
    }
  }
  function gate(card,kind){
    if(!config?.enabled){
      info(card,'CONFIGURATION REQUIRED — secure workspaces are disabled. No test identities or unauthenticated fallback are allowed. The shared demo organizations shown behind this dialog are illustrative only.');
      info(card,'Configure POSTFLIGHT_COGNITO_USER_POOL_ID, POSTFLIGHT_COGNITO_CLIENT_ID and POSTFLIGHT_COGNITO_DOMAIN on a private runtime. Production remains read-only.');
      return true;
    }
    if(!config.hostedDomain){
      info(card,'Cognito pool and client are configured, but the hosted login domain is missing.','error');
      return true;
    }
    if(!canOperate()){
      info(card,'Verified Amazon Cognito sign-in is required to access real tenant-scoped workspaces.');
      button(card,'Sign in with Cognito',()=>startLogin(kind),true);
      return true;
    }
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
        const sub=row(form,'Target Cognito user sub',{name:'sub',required:true,maxLength:100,
          placeholder:'User UUID from verified Cognito profile'});
        const role=row(form,'Role',{name:'role',options:['VIEWER','REVIEWER','ADMIN'].map(v=>({value:v,label:v}))});
        info(card,'Invitations are bound to an existing Cognito user UUID, expire after 24 hours, and can be used once. No invitation email is sent.');
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