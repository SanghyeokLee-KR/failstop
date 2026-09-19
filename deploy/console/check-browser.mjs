/** Actual local console verification. Credentials and signed file paths never enter evidence. */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const {chromium} = createRequire(path.join(root, 'web/package.json'))('playwright');
const base = process.env.CONSOLE_TEST_URL || 'http://127.0.0.1:53002';
const parsed = new URL(base);
assert(['127.0.0.1', 'localhost'].includes(parsed.hostname), 'Use a local console for this integration check');
const output = path.join(root, 'artifacts/operations-20260918/console');
await mkdir(output, {recursive:true});
const password = (await readFile(process.env.CONSOLE_TEST_PASSWORD_FILE || path.join(root, 'deploy/observability/.state/grafana-password'), 'utf8')).trim();
const browser = await chromium.launch({headless:true, channel:process.env.CONSOLE_TEST_BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined)});
const context = await browser.newContext({baseURL:base, viewport:{width:1440,height:900}, reducedMotion:'reduce'});
const page = await context.newPage();
const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
const evidence = {generatedAt:new Date().toISOString(), pages:[], checks:{}, errors};
async function settled(route) {
  await page.locator('.sidebar').waitFor({state:'visible'});
  const expected = route || await page.evaluate(()=>location.hash.slice(1) || 'overview');
  await page.waitForFunction((value)=>{
    const main = document.querySelector('#main');
    return main?.dataset.route === value && main.getAttribute('aria-busy') === 'false' && !main.querySelector('.skeleton');
  },expected,{timeout:30000});
  await page.locator('.loading-line').waitFor({state:'hidden',timeout:30000});
  await page.evaluate(()=>document.fonts.ready);
}
async function navigate(route) {
  if (await page.evaluate(()=>location.hash) !== '#' + route) {
    await page.locator(`.sidebar .nav-item[href="#${route}"]`).click();
    await page.waitForFunction((value)=>location.hash === '#' + value,route);
  }
  await settled(route);
}
async function capture(route, theme, width, height) {
  await navigate(route);
  const geometry = await page.evaluate(()=>({
    pageWidth:document.documentElement.scrollWidth, viewportWidth:innerWidth,
    pageHeight:document.documentElement.scrollHeight, viewportHeight:innerHeight,
    panels:document.querySelectorAll('.panel').length,
    clipped:[...document.querySelectorAll('.panel-head h2,.nav-item')].filter((node)=>node.scrollWidth>node.clientWidth+2).map((node)=>node.textContent.trim()),
    clippedDetails:[...document.querySelectorAll('.db-node .key-value:last-child,.runbook-step:last-of-type')].filter((node)=>node.getBoundingClientRect().bottom>node.closest('.panel-body').getBoundingClientRect().bottom+1).map((node)=>node.textContent.trim()),
    errors:[...document.querySelectorAll('.stale-banner')].map((node)=>node.textContent.trim()),
    route:document.querySelector('#main').dataset.route,
    busy:document.querySelector('#main').getAttribute('aria-busy'),
    heading:document.querySelector('#view h1')?.textContent,
  }));
  assert(geometry.pageWidth <= width + 1, `${route}: horizontal page overflow`);
  if (['overview','spring','db','aws'].includes(route)) assert(geometry.pageHeight <= height + 1, `${route}: vertical page overflow (${geometry.pageHeight})`);
  assert.equal(geometry.clipped.length,0,`${route}: clipped titles ${geometry.clipped.join(', ')}`);
  assert.equal(geometry.clippedDetails.length,0,`${route}: clipped details ${geometry.clippedDetails.join(', ')}`);
  assert.equal(geometry.route,route);
  assert.equal(geometry.busy,'false');
  await page.screenshot({path:path.join(output,`${route}-${theme}-${width}.png`),fullPage:true});
  evidence.pages.push({route,theme,width,height,...geometry});
  console.log(`${route} ${theme} ${width}x${height}: PASS`);
}
try {
  await page.goto(base);
  await page.locator('input[name="username"]').fill(process.env.CONSOLE_TEST_USER || 'operator');
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#login-form button[type="submit"]').click();
  await settled();
  await page.locator('#refresh-filter').selectOption('0');
  evidence.checks.login = true;
  for (const route of ['overview','spring','aws','db','customers','alerts','events','reports','settings','dashboards','datasources','users']) {
    await capture(route,'dark',1440,900);
  }
  await page.setViewportSize({width:1366,height:768});
  for (const route of ['overview','spring','aws','db']) await capture(route,'dark',1366,768);
  await page.setViewportSize({width:1440,height:900});
  await page.locator('[data-action="theme"]').first().click();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
  for (const route of ['overview','spring','aws','db']) await capture(route,'light',1440,900);
  await page.reload();
  await settled();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
  evidence.checks.themePersistence = true;
  await page.setViewportSize({width:1920,height:1080});
  for (const route of ['overview','spring','aws','db']) await capture(route,'light',1920,1080);
  await page.locator('[data-action="theme"]').first().click();
  for (const route of ['overview','spring','aws','db']) await capture(route,'dark',1920,1080);
  await navigate('reports');
  const reportLink = page.locator('a[href^="/api/report-file?"]').first();
  await reportLink.waitFor();
  const response = await context.request.get(await reportLink.getAttribute('href'));
  assert.equal(response.status(),200);
  const pdf = await response.body();
  assert(pdf.subarray(0,5).toString() === '%PDF-');
  evidence.checks.pdfDownload = {status:200,bytes:pdf.length};
  await navigate('spring');
  await page.keyboard.press('Control+k');
  await page.locator('#modal').waitFor({state:'visible'});
  await page.keyboard.press('Escape');
  evidence.checks.keyboardSearch = true;
  const rangeResponse = page.waitForResponse((result)=>result.url().includes('/api/metrics?') && new URL(result.url()).searchParams.get('range') === '21600');
  await page.locator('[data-range="21600"]').click();
  assert.equal((await rangeResponse).status(),200);
  await settled('spring');
  assert.equal(await page.locator('[data-range="21600"]').getAttribute('aria-pressed'),'true');
  evidence.checks.rangeSelection = true;
  const scopeResponse = page.waitForResponse((result)=>result.url().includes('/api/metrics?') && new URL(result.url()).searchParams.get('service') === 'apps');
  await page.locator('#service-filter').selectOption('apps');
  const scoped = await (await scopeResponse).json();
  assert(scoped.targets.length > 0 && scoped.targets.every((target)=>target.labels.service === 'apps'));
  await settled('spring');
  evidence.checks.serviceScope = {service:'apps',targets:scoped.targets.length};
  await page.locator('[data-expand]').first().click();
  await page.locator('#modal.expanded-dialog').waitFor({state:'visible'});
  assert(await page.locator('#modal svg').count() > 0);
  await page.keyboard.press('Escape');
  evidence.checks.chartExpansion = true;
  await navigate('reports');
  await page.locator('#table-search').fill('not-a-real-report-94817');
  assert.equal(await page.locator('[data-report]').count(),0);
  await page.locator('#table-search').fill('');
  assert(await page.locator('[data-report]').count() > 0);
  evidence.checks.reportSearch = true;
  await navigate('settings');
  await page.locator('#settings-form select[name="range"]').selectOption('3600');
  await page.locator('#settings-form select[name="refresh"]').selectOption('0');
  await page.locator('#settings-form button[type="submit"]').click();
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('msp.console.preferences')).range),3600);
  evidence.checks.preferencesSave = true;
  await navigate('db');
  const resetScopeResponse = page.waitForResponse((result)=>result.url().includes('/api/metrics?') && new URL(result.url()).searchParams.get('service') === '');
  await page.locator('#service-filter').selectOption('');
  const fixture = structuredClone(await (await resetScopeResponse).json());
  await settled('db');
  await page.locator('[data-action="runbook"]').click();
  await page.locator('#modal').waitFor({state:'visible'});
  assert((await page.locator('#modal').textContent()).includes('격리'));
  await page.keyboard.press('Escape');
  evidence.checks.recoveryRunbook = true;
  const standby = fixture.metrics.db_recovery.series.find((series)=>series.value === 1)?.labels.instance;
  assert(standby,'Live standby recovery-mode sample required');
  for (const series of fixture.metrics.db_up.series) if (series.labels.instance === standby) series.value = 0;
  fixture.alerts = [{id:'ui-fixture-only',name:'DatabaseConnectionFailed',state:'firing',severity:'critical',summary:'Standby DB 접속 장애',description:'브라우저 표시 검증용 응답',activeAt:new Date().toISOString(),labels:{instance:standby,node:'Standby',role:'standby'}}];
  await page.route('**/api/metrics?*',(route)=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(fixture)}));
  await page.locator('[data-action="refresh"]').first().click();
  await settled('db');
  assert.equal(await page.locator('.hero-status.danger').count(),1);
  assert(await page.locator('.db-node .state-badge.danger').count() > 0);
  assert.equal(await page.locator('.notification-count').textContent(),'1');
  await page.screenshot({path:path.join(output,'db-critical-ui-fixture.png'),fullPage:true});
  evidence.checks.criticalFixture = {browserResponseOnly:true,actualDatabaseStopped:false,status:'PASS'};
  await page.unroute('**/api/metrics?*');
  await page.route('**/api/metrics?*',(route)=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'관측 경로 응답 없음 · 표시 검증'})}));
  await navigate('overview');
  assert.equal(await page.locator('.hero-status.unknown').count(),1);
  assert.equal(await page.locator('.stale-banner.danger').count(),1);
  await page.screenshot({path:path.join(output,'collection-error-ui-fixture.png'),fullPage:true});
  evidence.checks.collectionFailureFixture = {browserResponseOnly:true,actualPrometheusStopped:false,status:'PASS'};
  await page.unroute('**/api/metrics?*');
  assert.equal(errors.length,0,errors.join('\n'));
  evidence.status = 'PASS';
} catch (error) {
  evidence.status = 'FAIL'; evidence.failure = error.message;
  await page.screenshot({path:path.join(output,'failure.png'),fullPage:true});
  throw error;
} finally {
  await writeFile(path.join(output,'browser-check.json'),JSON.stringify(evidence,null,2)+'\n');
  await browser.close();
}
