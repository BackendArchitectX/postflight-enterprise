import sys,time
from pathlib import Path
from playwright.sync_api import sync_playwright
url=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8098/'
errors=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
    page=browser.new_page(viewport={'width':1440,'height':920},device_scale_factor=1)
    page.on('pageerror',lambda error:errors.append(str(error)))
    page.goto(url,wait_until='networkidle')
    page.locator('.brand-name').wait_for()
    assert page.locator('body').inner_text().count('LOCAL DEVELOPMENT')>=1
    print('✓ Browser loaded server-backed PostFlight')
    for route,title in [('overview','Mission Control'),('workspaces','Workspaces'),('studio','Content Studio'),('validation','Validation Center'),('media','Media Intelligence'),('policies','Policy Studio'),('workflows','Workflow Builder'),('reviews','Review Center'),('releases','Release Center'),('incidents','Compliance & Incidents'),('integrations','Integrations'),('settings','Administration')]:
        page.goto(url+'#/'+route)
        page.locator('h1.page-title').wait_for()
        assert page.locator('h1.page-title').inner_text()==title,(route,page.locator('h1.page-title').inner_text())
    print('✓ All 12 navigation routes render without JS errors')
    page.goto(url+'#/studio');page.get_by_role('button',name='New draft').first.click();page.locator('#modalDraftTitle').fill('Browser end-to-end draft');page.get_by_role('button',name='Create draft').click();page.wait_for_timeout(500)
    assert page.locator('#draftTitle').input_value()=='Browser end-to-end draft'; print('✓ New draft created through live API')
    page.locator('#draftBody').fill('Get guaranteed returns on investments instantly!')
    page.locator('#altText').fill('Campaign visual')
    page.get_by_role('button',name='Run preflight').first.click()
    page.wait_for_timeout(800)
    assert page.locator('h1.page-title').inner_text()=='Validation Center';assert 'Unverified guarantee of returns' in page.locator('body').inner_text()
    print('✓ Preflight writes and reports critical violations')
    page.goto(url+'#/studio')
    page.locator('#draftBody').fill('Learn about savings and budgeting habits that can support your goals.')
    page.locator('#altText').fill('An illustration of a family planning a budget')
    page.get_by_role('button',name='Run preflight').first.click()
    page.wait_for_timeout(800)
    assert '0 checks requiring review' in page.locator('body').inner_text(),page.locator('body').inner_text()[-500:]
    print('✓ Remediation and green rerun')
    page.screenshot(path='/mnt/data/PostFlight_Enterprise/docs/runnable-validation.png',full_page=True)
    page.goto(url+'#/reviews');page.get_by_role('button',name='Approve').first.click();page.wait_for_timeout(500)
    assert 'Server review decision: Approved' in page.locator('body').inner_text(),page.locator('body').inner_text()[-700:]
    print('✓ Review approval persisted via API')
    page.goto(url+'#/releases');page.get_by_role('button',name='Issue release certificate').click();page.wait_for_timeout(600)
    assert 'Local release certificate' in page.locator('body').inner_text(),page.locator('body').inner_text()[-500:]
    print('✓ Local HMAC release certificate issued in UI')
    page.goto(url+'#/overview')
    page.screenshot(path='/mnt/data/PostFlight_Enterprise/docs/runnable-mission-control.png',full_page=True)
    assert not errors,errors
    print('✓ Browser console has no unhandled JS errors')
    browser.close()
print('PASS — 12 routes + create/edit/validate/review/release UI workflow')
