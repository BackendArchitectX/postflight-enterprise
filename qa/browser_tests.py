#!/usr/bin/env python3
"""Optional real-Chromium UI checks; runs against temporary isolated Java server.
Requires: Python 3 + playwright + installed Chromium. Never uses live port 8080 or user data.
"""
import base64, csv, io, json, os, pathlib, re, shutil, socket, subprocess, sys, tempfile, time, traceback
from datetime import datetime, timezone
from playwright.sync_api import sync_playwright, expect

ROOT=pathlib.Path(__file__).resolve().parent.parent
REPORT=ROOT/'qa-reports';REPORT.mkdir(exist_ok=True)
RESULTS=[];GROUP='Browser startup';BASE='';PAGE=None

def check(name,fn):
    try:
        value=fn()
        if value is False: raise AssertionError('Expected condition not met')
        RESULTS.append((GROUP,name,'PASS',''))
        print(' PASS',name)
    except Exception as ex:
        msg=str(ex)[:450];RESULTS.append((GROUP,name,'FAIL',msg));print(' FAIL',name,'-',msg)
        if PAGE is not None:
            try: PAGE.screenshot(path=str(REPORT/f'browser-error-{len(RESULTS):03d}.png'),full_page=True,timeout=5000)
            except: pass

def group(name):
    global GROUP
    GROUP=name;print('\n['+GROUP+']')

def assert_that(cond,msg='Condition did not hold'):
    if not cond: raise AssertionError(msg)

def route(name):
    PAGE.goto(BASE+'/#/'+name,wait_until='networkidle',timeout=10000)
    PAGE.locator('h1.page-title').wait_for(timeout=5000)

def click_action(name):
    PAGE.locator('[data-action="'+name+'"]').first.click(timeout=5000)

def check_modal(title): assert_that(PAGE.locator('.modal h2').inner_text()==title, PAGE.locator('.modal h2').inner_text())

def resolve_jdk():
    """Choose javac and java from the SAME JDK 17+ install.

    Windows can resolve bare `java` in subprocesses to Java 8 even when
    `javac` and the interactive PowerShell `java` are version 21. Passing
    absolute paths avoids shell/search-order ambiguity.
    """
    exe = '.exe' if os.name == 'nt' else ''
    candidates = []
    for env_name in ('POSTFLIGHT_QA_JDK_HOME', 'JAVA_HOME'):
        value = os.environ.get(env_name)
        if value:
            candidates.append((env_name, pathlib.Path(value.strip('"')).expanduser()))
    javac_on_path = shutil.which('javac')
    if javac_on_path:
        candidates.append(('javac on PATH', pathlib.Path(javac_on_path).resolve().parent.parent))
    tried = []
    seen = set()
    for origin, home in candidates:
        if str(home).lower() in seen:
            continue
        seen.add(str(home).lower())
        java = home / 'bin' / ('java' + exe)
        javac = home / 'bin' / ('javac' + exe)
        if not java.is_file() or not javac.is_file():
            tried.append(f'{origin}: missing java/javac in {home / "bin"}')
            continue
        try:
            runtime = subprocess.run([str(java), '-version'], capture_output=True,
                text=True, errors='replace', timeout=15)
            compiler = subprocess.run([str(javac), '-version'], capture_output=True,
                text=True, errors='replace', timeout=15)
            rtext = runtime.stdout + runtime.stderr
            ctext = compiler.stdout + compiler.stderr
            rmatch = re.search(r'(?:openjdk|java) version ["\'](\d+)(?:\.(\d+))?', rtext)
            cmatch = re.search(r'javac\s+(\d+)(?:\.(\d+))?', ctext)
            rmajor = int(rmatch.group(2)) if rmatch and rmatch.group(1) == '1' and rmatch.group(2) else int(rmatch.group(1)) if rmatch else 0
            cmajor = int(cmatch.group(2)) if cmatch and cmatch.group(1) == '1' and cmatch.group(2) else int(cmatch.group(1)) if cmatch else 0
            if runtime.returncode == 0 and compiler.returncode == 0 and rmajor >= 17 and cmajor >= 17:
                return str(java), str(javac), origin, rtext.strip(), ctext.strip()
            tried.append(f'{origin}: java={rmajor or "unknown"}, javac={cmajor or "unknown"}')
        except Exception as exc:
            tried.append(f'{origin}: {type(exc).__name__}: {exc}')
    raise RuntimeError('Could not locate a matching Java 17+ JDK. '
        'Run the tests in the PowerShell window where java -version shows 21, '
        'or set POSTFLIGHT_QA_JDK_HOME to your JDK installation directory. '
        'Attempted: ' + '; '.join(tried or ['JAVA_HOME and javac on PATH were unavailable']))

def run():
 global BASE,PAGE
 with tempfile.TemporaryDirectory(prefix='postflight-qa-browser-') as temp:
    work=pathlib.Path(temp);(work/'docs').mkdir();shutil.copy2(ROOT/'docs/seed.json',work/'docs/seed.json');shutil.copytree(ROOT/'web',work/'web')
    classes=work/'classes';classes.mkdir()
    sources=[str(x) for base in [ROOT/'backend/src/main/java/com/postflight/core',ROOT/'backend/src/main/java/com/postflight/local'] for x in base.rglob('*.java')]
    java_exe,javac_exe,jdk_source,java_version,javac_version=resolve_jdk()
    print('Using Java 17+ JDK ('+jdk_source+'): '+java_exe, flush=True)
    subprocess.run([javac_exe,'--release','17','-encoding','UTF-8','-d',str(classes),*sources],check=True,capture_output=True,text=True)
    with socket.socket() as so:so.bind(('127.0.0.1',0));port=so.getsockname()[1]
    BASE=f'http://127.0.0.1:{port}'
    # Preserve the child JVM output. Previously stdout/stderr were discarded,
    # which obscured missing Java runtimes, filesystem errors and bind failures.
    log_path=REPORT/'POSTFLIGHT_BROWSER_SERVER.log'
    java_cmd=[java_exe,'--add-modules','jdk.httpserver','-cp',str(classes),'com.postflight.local.Main',str(port)]
    with log_path.open('w',encoding='utf-8') as log:
        log.write('PostFlight optional browser QA: isolated temporary Java server\n')
        log.write('Selected JDK source: '+jdk_source+'\n')
        log.write('java executable: '+java_exe+'\n')
        log.write('javac executable: '+javac_exe+'\n')
        log.write('java -version: '+java_version+'\n')
        log.write('javac -version: '+javac_version+'\n')
        log.flush()
        service=subprocess.Popen(java_cmd,cwd=work,stdout=log,stderr=subprocess.STDOUT)
    try:
        import urllib.request
        last_error='No response from temporary server'
        started=False
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            exit_code=service.poll()
            if exit_code is not None:
                last_error='The temporary Java server exited early (exit code '+str(exit_code)+')'
                break
            try:
                with urllib.request.urlopen(BASE+'/api/health',timeout=.5) as response:
                    if response.status==200:
                        started=True
                        break
                    last_error='Health endpoint returned HTTP '+str(response.status)
            except Exception as err:
                last_error=type(err).__name__+': '+str(err)
            time.sleep(.2)
        if not started:
            try:
                log_tail=log_path.read_text(encoding='utf-8',errors='replace')[-4000:]
            except Exception as err:
                log_tail='Cannot read Java startup log: '+str(err)
            raise RuntimeError('Temporary QA server did not start: '+last_error+
                '\nJava startup log: '+str(log_path)+'\n'+log_tail)
        print('Temporary QA server is UP at '+BASE)
        with sync_playwright() as p:
            chrome=os.getenv('POSTFLIGHT_CHROMIUM_PATH') or ('/usr/bin/chromium' if pathlib.Path('/usr/bin/chromium').exists() else None)
            browser=p.chromium.launch(headless=True,executable_path=chrome,args=['--no-sandbox','--disable-dev-shm-usage'] if os.name!='nt' else [])
            ctx=browser.new_context(viewport={'width':1440,'height':900},accept_downloads=True,permissions=['clipboard-read','clipboard-write'])
            PAGE=ctx.new_page(); PAGE.set_default_timeout(3500); errors=[];PAGE.on('pageerror',lambda e:errors.append(str(e)))
            PAGE.goto(BASE+'/',wait_until='domcontentloaded',timeout=10000)
            if PAGE.locator('.brand-name').count()==0:
                raise RuntimeError('This browser could not open the isolated localhost test server. Browser UI tests not executed (environment may block localhost).')
            group('Navigation and global controls')
            check('Loads Mission Control',lambda: (route('overview'), assert_that(PAGE.locator('h1').inner_text()=='Mission Control')))
            nav=[('overview','Mission Control'),('workspaces','Workspaces'),('studio','Content Studio'),('validation','Validation Center'),('media','Media Intelligence'),('policies','Policy Studio'),('workflows','Workflow Builder'),('reviews','Review Center'),('releases','Release Center'),('incidents','Compliance & Incidents'),('integrations','Integrations'),('settings','Administration')]
            for slug,label in nav:
                def visit(slug=slug,label=label):
                    PAGE.locator('.nav-item[data-route="'+slug+'"]').click();PAGE.locator('h1.page-title').wait_for();assert_that(PAGE.locator('h1').inner_text()==label)
                check('Sidebar click '+label,visit)
            check('Global search opens',lambda: (click_action('search'),assert_that(PAGE.locator('#commandSearch').is_visible())))
            check('Search filters sections',lambda: (PAGE.locator('#commandSearch').fill('Policy'), assert_that(PAGE.locator('.search-item[data-route="policies"]').count()==1)))
            check('Search result navigates to policy',lambda:(PAGE.locator('.search-item[data-route="policies"]').click(),assert_that(PAGE.locator('h1').inner_text()=='Policy Studio')))
            check('Ctrl+K opens keyboard command palette',lambda:(PAGE.keyboard.press('Control+k'),assert_that(PAGE.locator('#commandSearch').is_visible())))
            check('Escape closes search',lambda:(PAGE.keyboard.press('Escape'),assert_that(PAGE.locator('.search-pop').count()==0)))
            check('Notifications produces a toast',lambda:(click_action('notifications'),assert_that('No new notifications' in PAGE.locator('#toasts').inner_text())))
            check('Browser Back works',lambda:(PAGE.go_back(wait_until='networkidle'),assert_that(PAGE.locator('h1').count()==1)))
            check('Browser Forward works',lambda:(PAGE.go_forward(wait_until='networkidle'),assert_that(PAGE.locator('h1').count()==1)))
            route('overview')
            group('Mission Control and workspaces')
            for x in ['7D','14D','30D']:
                check('Chart timeframe '+x,lambda x=x:(PAGE.locator('[data-timeframe="'+x+'"]').click(),assert_that('selected' in PAGE.locator('[data-timeframe="'+x+'"]').get_attribute('class'))))
            check('New validation opens validation',lambda:(click_action('startValidation'),PAGE.wait_for_timeout(500),assert_that(PAGE.locator('h1').inner_text()=='Validation Center')))
            route('overview')
            check('Open validation queue navigates',lambda:(PAGE.locator('[data-route="validation"]').filter(has_text='Open validation queue').click(),assert_that(PAGE.locator('h1').inner_text()=='Validation Center')))
            route('workspaces')
            check('Workspace filtering selects Meridian',lambda:(PAGE.locator('#workspaceSearch').fill('Meridian'),assert_that(PAGE.locator('[data-workspace]').count()==1)))
            check('Workspace selection updates breadcrumb',lambda:(PAGE.locator('[data-workspace="Meridian Labs"]').click(),assert_that('Meridian Labs' in PAGE.locator('body').inner_text())))
            check('Workspace project row opens Studio',lambda:(PAGE.locator('tr[data-route="studio"]').first.click(),assert_that(PAGE.locator('h1').inner_text()=='Content Studio')))
            route('workspaces')
            check('Invite member shows demo-only popup',lambda:(click_action('invite'),check_modal('Design preview action')))
            check('Cancel closes invite popup',lambda:(click_action('closeModal'),assert_that(PAGE.locator('.modal').count()==0)))
            check('New workspace opens demo-only popup',lambda:(click_action('newWorkspace'),check_modal('Design preview action')))
            check('X closes preview popup',lambda:(PAGE.locator('button.modal-close').click(),assert_that(PAGE.locator('.modal').count()==0)))

            group('Content Studio - real browser interaction')
            route('studio')
            check('New draft dialog opens',lambda:(click_action('newDraft'),check_modal('Create a new draft')))
            check('Empty title rejected',lambda:(PAGE.locator('.modal-actions [data-action="confirmDraft"]').click(),assert_that('Enter a draft title' in PAGE.locator('#toasts').inner_text())))
            check('Cancel closes New draft',lambda:(PAGE.locator('.modal-actions [data-action="closeModal"]').click(),assert_that(PAGE.locator('.modal').count()==0)))
            check('New draft X closes popup',lambda:(click_action('newDraft'),PAGE.locator('.modal-close').click(),assert_that(PAGE.locator('.modal').count()==0)))
            check('Click outside closes popup',lambda:(click_action('newDraft'),PAGE.locator('.overlay').click(position={'x':5,'y':5},force=True),assert_that(PAGE.locator('.modal').count()==0)))
            def create():
                # The modal submits to Java asynchronously. Wait for the new draft to
                # become the selected editor record before inspecting persistence.
                click_action('newDraft')
                PAGE.locator('#modalDraftTitle').fill('Browser QA Financial Awareness')
                PAGE.locator('.modal-actions [data-action="confirmDraft"]').click()
                # Use Playwright's native locator assertion. wait_for_function on a
                # JavaScript source string requires unsafe-eval, which PostFlight's
                # deliberately strict Content Security Policy correctly blocks.
                expect(PAGE.locator('#draftTitle')).to_have_value(
                    'Browser QA Financial Awareness', timeout=10000
                )
                draft_id = PAGE.locator('#draftSelect').input_value()
                assert_that(draft_id.startswith('PF-'), 'New draft was not selected')
                response = PAGE.context.request.get(BASE+'/api/drafts', timeout=10000)
                assert_that(response.ok, 'The Java draft list request failed')
                drafts = response.json()
                assert_that(any(d.get('id')==draft_id and d.get('title')=='Browser QA Financial Awareness' for d in drafts),
                    'Newly created draft did not persist in the Java API')
            check('Create new draft persists',create)
            newdraft=PAGE.locator('#draftSelect').input_value()
            check('Saved drafts selects new entry',lambda:assert_that(newdraft.startswith('PF-')))
            check('Caption typing updates character counter',lambda:(PAGE.locator('#draftBody').fill('Get guaranteed returns on your investments today!'),assert_that('characters' in PAGE.locator('#charCount').inner_text())))
            check('Live social caption preview changes',lambda:assert_that('Get guaranteed returns' in PAGE.locator('#socialPreviewText').inner_text()))
            check('Image alt text can be edited',lambda:(PAGE.locator('#altText').fill(''),assert_that(PAGE.locator('#altText').input_value()=='')))
            for platform in ['LinkedIn','X','Instagram','Facebook']:
                check(platform+' tab selection',lambda platform=platform:(PAGE.locator('[data-platform="'+platform+'"]').click(),assert_that('selected' in PAGE.locator('[data-platform="'+platform+'"]').get_attribute('class'))))
            check('Save draft triggers real backend save',lambda:(click_action('saveDraft'),PAGE.wait_for_timeout(250),assert_that('persisted' in PAGE.locator('#toasts').inner_text().lower())))
            check('Draft persists after refresh',lambda:(PAGE.reload(wait_until='networkidle'),assert_that('guaranteed returns' in PAGE.locator('#draftBody').input_value())))
            check('LinkedIn platform tab works after refresh',lambda:(PAGE.locator('[data-platform="LinkedIn"]').click(),assert_that('selected' in PAGE.locator('[data-platform="LinkedIn"]').get_attribute('class'))))
            check('Run preflight finds risky text',lambda:(PAGE.locator('[data-action="runPreflight"]').first.click(),PAGE.wait_for_timeout(600),assert_that(PAGE.locator('h1').inner_text()=='Validation Center' and 'Unverified guarantee' in PAGE.locator('body').inner_text())))
            group('Validation Center browser actions')
            check('Critical finding selected',lambda:assert_that(PAGE.locator('.issue-item[data-finding="FIN-MKT-001"]').count()==1))
            for label in ('all','critical','high','medium'):
                check('Severity filter '+label,lambda label=label:(PAGE.locator('[data-validation-tab="'+label+'"]').click(),assert_that('selected' in PAGE.locator('[data-validation-tab="'+label+'"]').get_attribute('class'))))
            check('Finding inspector switches to High',lambda:(PAGE.locator('[data-validation-tab="all"]').click(),PAGE.locator('.issue-item[data-finding="FIN-MKT-014"]').click(),assert_that('Mandatory risk disclosure' in PAGE.locator('body').inner_text())))
            check('Return to editor navigates Studio',lambda:(PAGE.get_by_role('button',name='Return to editor').click(),assert_that(PAGE.locator('h1').inner_text()=='Content Studio')))
            check('Remediation can be saved',lambda:(PAGE.locator('#draftBody').fill('Practical investment tips. Investing involves risk and outcomes may vary.'),PAGE.locator('#altText').fill('Simple illustration'),click_action('saveDraft'),PAGE.wait_for_timeout(250)))
            check('After remediation preflight shows PASS',lambda:(PAGE.locator('[data-action="runPreflight"]').first.click(),PAGE.wait_for_timeout(400),assert_that('PASS' in PAGE.locator('body').inner_text() and '0 checks requiring review' in PAGE.locator('body').inner_text())))
            check('Rerun checks keeps latest PASS',lambda:(click_action('runPreflight'),PAGE.wait_for_timeout(350),assert_that('0 checks requiring review' in PAGE.locator('body').inner_text())))
            group('Review Center and release UI')
            route('reviews')
            for tab in ('pending','changes','completed','all'):
                check('Review tab '+tab,lambda tab=tab:(PAGE.locator('[data-review-tab="'+tab+'"]').click(),assert_that('selected' in PAGE.locator('[data-review-tab="'+tab+'"]').get_attribute('class'))))
            check('QA draft can be selected in inbox',lambda:(PAGE.locator('[data-review-tab="all"]').click(),PAGE.locator('[data-review-select="'+newdraft+'"]').click(),assert_that('QA' in PAGE.locator('body').inner_text())))
            check('Approve button uses server',lambda:(click_action('approveReview'),PAGE.wait_for_timeout(500),assert_that('Approved' in PAGE.locator('#toasts').inner_text())))
            route('releases')
            check('Issue certificate from approved selected draft',lambda:(click_action('certificate'),PAGE.wait_for_timeout(450),check_modal('Local release certificate')))
            check('Certificate popup has copy details',lambda:assert_that(PAGE.locator('[data-action="copyCertificate"]').count()==1))
            check('Certificate modal Cancel works',lambda:(PAGE.locator('.modal-actions [data-action="closeModal"]').click(),assert_that(PAGE.locator('.modal').count()==0)))
            check('New release appears in register',lambda:assert_that(PAGE.locator('[data-release]').count()>0))
            check('Select sample release changes evidence',lambda:(PAGE.locator('[data-release="PF-REL-2048"]').click(),assert_that('PF-REL-2048' in PAGE.locator('body').inner_text())))
            check('Selected sample row still marked In review',lambda:assert_that('In review' in PAGE.locator('body').inner_text()))

            group('Policy, workflow, media, incidents and settings')
            route('policies')
            for tab in ('library','simulator','versions'):
                check('Policy tab '+tab,lambda tab=tab:(PAGE.locator('[data-policy-tab="'+tab+'"]').click(),assert_that('selected' in PAGE.locator('[data-policy-tab="'+tab+'"]').get_attribute('class'))))
            check('Add rule dialog opens',lambda:(click_action('newRule'),check_modal('Create organization rule')))
            check('Blank policy rule rejected',lambda:(PAGE.locator('[data-action="confirmRule"]').click(),assert_that('required' in PAGE.locator('#toasts').inner_text())))
            check('Rule dialog closes with Escape',lambda:(PAGE.keyboard.press('Escape'),assert_that(PAGE.locator('.modal').count()==0)))
            check('Publish version is demo-only',lambda:(click_action('publishPolicy'),check_modal('Design preview action')))
            PAGE.locator('.modal-close').click()
            route('workflows')
            for tab in ('canvas','executions','templates'):
                check('Workflow tab '+tab,lambda tab=tab:(PAGE.locator('[data-workflow-tab="'+tab+'"]').click(),assert_that('selected' in PAGE.locator('[data-workflow-tab="'+tab+'"]').get_attribute('class'))))
            check('Return to workflow canvas', lambda:(PAGE.locator('[data-workflow-tab="canvas"]').click(), assert_that('selected' in PAGE.locator('[data-workflow-tab="canvas"]').get_attribute('class'))))
            for node in ['Content validation','Risk classification','Legal review','Brand review','Release approval']:
                check('Workflow selects '+node,lambda node=node:(PAGE.locator('[data-flow-node="'+node+'"]').click(),assert_that(node in PAGE.locator('body').inner_text())))
            check('Workflow test simulates',lambda:(click_action('testWorkflow'),PAGE.wait_for_timeout(450),assert_that('simulated' in PAGE.locator('#toasts').inner_text())))
            route('media')
            for tab in ('all','review','ready'):
                check('Media tab '+tab,lambda tab=tab:(PAGE.locator('[data-media-tab="'+tab+'"]').click(),assert_that('selected' in PAGE.locator('[data-media-tab="'+tab+'"]').get_attribute('class'))))
            check('Upload media dialog opens',lambda:(click_action('uploadMedia'),check_modal('Upload local media')))
            check('No media file rejected',lambda:(PAGE.locator('[data-action="confirmMedia"]').click(),assert_that('Select a PNG' in PAGE.locator('#toasts').inner_text())))
            PAGE.locator('.modal-close').click()
            route('incidents')
            check('Incident detail selection works',lambda:(PAGE.locator('[data-incident]').first.click(),assert_that('Incident details' in PAGE.locator('body').inner_text())))
            def resolve_incident():
                incident_id = PAGE.locator('tr[data-incident].selected').get_attribute('data-incident')
                assert_that(incident_id is not None, 'No incident selected before resolving')
                click_action('resolveIncident')
                # App's success toast is 'Incident status saved on server', not
                # 'resolved'. Check both server response and the rendered status.
                expect(PAGE.locator('#toasts')).to_contain_text(
                    'Incident status saved on server', timeout=8000
                )
                expect(PAGE.locator('tr[data-incident="'+incident_id+'"]')).to_contain_text(
                    'Resolved', timeout=8000
                )
                result=PAGE.context.request.get(BASE+'/api/bootstrap',timeout=10000)
                assert_that(result.ok,'Could not read incident status from server')
                alerts=result.json().get('alerts',[])
                assert_that(any(a.get('id')==incident_id and a.get('status')=='Resolved' for a in alerts),
                    'Server did not persist the incident status as Resolved')
            check('Mark incident resolved',resolve_incident)
            check('Escalation is demo-only',lambda:(click_action('escalateIncident'),check_modal('Design preview action')))
            PAGE.locator('.modal-close').click()
            route('integrations')
            check('Integration Details opens demo preview',lambda:(PAGE.locator('[data-action="integrationDetails"]').first.click(),check_modal('Design preview action')))
            PAGE.locator('.modal-close').click()
            check('Add integration opens demo preview',lambda:(click_action('addIntegration'),check_modal('Design preview action')))
            PAGE.locator('.modal-close').click()
            route('settings')
            for name in ['requireLegal','audit','notify']:
                check('Governance UI toggle '+name,lambda name=name:(PAGE.locator('[data-toggle="'+name+'"]').click(),assert_that(PAGE.locator('[data-toggle="'+name+'"]').count()==1)))
            check('Reset demo data confirmation CANCEL',lambda:(PAGE.once('dialog',lambda d:d.dismiss()),click_action('resetDemo'),PAGE.wait_for_timeout(200),assert_that(PAGE.locator('h1').inner_text()=='Administration')))
            check('No unhandled JavaScript exceptions',lambda:assert_that(not errors,' / '.join(errors[:5])))
            def mobile_overflow():
                PAGE.set_viewport_size({'width':390,'height':844})
                dimensions=PAGE.evaluate('''() => ({viewport: innerWidth,
                    html: document.documentElement.scrollWidth,
                    body: document.body.scrollWidth,
                    offenders: Array.from(document.querySelectorAll('body *'))
                      .filter(el => { const r=el.getBoundingClientRect();
                        const style=getComputedStyle(el);
                        return r.right>innerWidth+2 && r.left<innerWidth &&
                          style.display!=='none' && style.visibility!=='hidden'; })
                      .slice(0,10)
                      .map(el => ({tag:el.tagName.toLowerCase(),
                          selector:el.id?'#'+el.id:('.'+String(el.className||'').trim().split(/\\s+/).join('.')),
                          right:Math.round(el.getBoundingClientRect().right)}))
                })''')
                assert_that(dimensions['html']<=dimensions['viewport'],
                    'Horizontal overflow at 390px (document '+str(dimensions['html'])+
                    'px, viewport '+str(dimensions['viewport'])+'px). Offending elements: '+
                    json.dumps(dimensions['offenders']))
            check('Mobile screen has no horizontal overflow',mobile_overflow)
            browser.close()
    finally:
        service.terminate()
        try: service.wait(timeout=4)
        except subprocess.TimeoutExpired:service.kill()

def report():
    passed=sum(x[2]=='PASS' for x in RESULTS);failed=len(RESULTS)-passed
    with open(REPORT/'POSTFLIGHT_BROWSER_RESULTS.csv','w',newline='',encoding='utf-8-sig') as f:
        writer=csv.writer(f);writer.writerow(['Section','Test','Outcome','Details']);writer.writerows(RESULTS)
    with open(REPORT/'POSTFLIGHT_BROWSER_RESULTS.json','w',encoding='utf-8') as f:
        json.dump({'passed':passed,'failed':failed,'automatedBrowserChecks':len(RESULTS),'note':'Real headless Chromium browser, isolated temp server. Does not claim entire 148-case checklist is complete.','results':[dict(zip(['section','test','outcome','details'],r)) for r in RESULTS]},f,indent=2)
    print(f'\nBrowser QA summary: {len(RESULTS)} checks | {passed} PASS | {failed} FAIL')
    return failed

if __name__=='__main__':
    try:run()
    except Exception as e:
        group('Automation setup');RESULTS.append((GROUP,'Browser automation setup','FAIL',str(e)));traceback.print_exc()
    status=report()
    sys.exit(1 if status else 0)
