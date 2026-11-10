(() => {
  'use strict';
  // Clerk owns the interactive session. Fetch a fresh signed JWT for every API call.
  // PostFlight never persists session tokens in browser storage or URLs.
  let config=null,overlay=null,busy=false,clerkPromise=null,pendingKind=null;
  const managed=new Set(['newWorkspace','invite','secureWorkspaces','joinWorkspace']);
  const el=(tag,content,cls)=>{
    const n=document.createElement(tag);if(content!==undefined)n.textContent=content;
    if(cls)n.className=cls;return n;
  };
  async function json(method,path,body){
    const headers={Accept:'application/json'};
    if(path.startsWith('/api/secure/')&&path!=='/api/secure/config'){
      const clerk=await initClerk();
      const token=await clerk.session?.getToken();
      if(!token)throw new Error('Sign in with Clerk before using secure workspaces.');
      headers.Authorization='Bearer '+token;
    }
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
  function loadScript(src,attributes={}){
    return new Promise((resolve,reject)=>{
      const script=document.createElement('script');
      script.src=src;script.async=true;script.crossOrigin='anonymous';
      for(const [name,value] of Object.entries(attributes))script.setAttribute(name,value);
      script.onload=resolve;
      script.onerror=()=>reject(new Error('Could not load Clerk from its trusted domain'));
      document.head.append(script);
    });
  }
  async function initClerk(){
    if(!config?.enabled||!config?.publishableKey||!config?.frontendApi)
      throw new Error('Clerk is not configured on this private server.');
    if(!window.isSecureContext)
      throw new Error('Clerk needs HTTPS or localhost to sign in.');
    if(!clerkPromise)clerkPromise=(async()=>{
      const api=new URL(config.frontendApi);
      if(api.protocol!=='https:'||api.origin!==config.frontendApi)
        throw new Error('Invalid Clerk Frontend API configuration');
      await loadScript(config.frontendApi+'/npm/@clerk/ui@1/dist/ui.browser.js');
      await loadScript(config.frontendApi+'/npm/@clerk/clerk-js@6/dist/clerk.browser.js',{
        'data-clerk-publishable-key':config.publishableKey
      });
      if(!window.Clerk||!window.__internal_ClerkUICtor)
        throw new Error('Clerk sign-in UI could not initialize');
      await window.Clerk.load({ui:{ClerkUI:window.__internal_ClerkUICtor}});
      window.Clerk.addListener(({session})=>{
        if(session&&pendingKind){
          const requested=pendingKind;pendingKind=null;
          window.Clerk.closeSignIn();
          void openPage(requested);
        }
      });
      return window.Clerk;
    })().catch(e=>{clerkPromise=null;throw e;});
    return clerkPromise;
  }
  async function startLogin(kind){
    try{
      const clerk=await initClerk();
      if(clerk.isSignedIn){await openPage(kind);return;}
      pendingKind=kind;
      close(); // Clerk's sign-in modal is rendered at the document root.
      clerk.openSignIn();
    }catch(e){
      const card=overlay?.querySelector('.ops-dialog')||modal('Clerk sign-in','Sign-in could not start.');
      info(card,e.message,'error');
    }
  }
  async function boot(){
    try{config=await json('GET','/api/secure/config');}
    catch(e){const card=modal('Clerk configuration','Could not load private sign-in settings.');
      info(card,e.message,'error');}
  }
  async function gate(card,kind){
    if(!config?.enabled){
      info(card,'CONFIGURATION REQUIRED — secure workspaces are disabled. No test identities or unauthenticated fallback are allowed. The sample organizations are illustrative only.');
      info(card,'Set POSTFLIGHT_CLERK_PUBLISHABLE_KEY on a private runtime. The public Render demo remains read-only.');
      return true;
    }
    try{
      const clerk=await initClerk();
      if(!clerk.isSignedIn){
        info(card,'Clerk sign-in is required before you can manage real workspaces.');
        button(card,'Sign in with Clerk',()=>startLogin(kind),true);
        return true;
      }
      return false;
    }catch(e){info(card,e.message,'error');return true;}
  }
  async function showList(card){
    const me=await json('GET','/api/secure/me');
    info(card,'Signed in as '+me.sub+' — this is your Clerk user ID for receiving invitations.');
    button(card,'Sign out of Clerk',async()=>{
      const clerk=await initClerk();await clerk.signOut();close();
    });
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
    if(await gate(card,kind))return;
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
          placeholder:'user_... from the other person's signed-in PostFlight account'});
        const role=row(form,'Role',{name:'role',options:['VIEWER','REVIEWER','ADMIN'].map(v=>({value:v,label:v}))});
        info(card,'Invitations are bound to an existing Clerk user ID, expire after 24 hours, and can be used once. No invitation email is sent.');
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