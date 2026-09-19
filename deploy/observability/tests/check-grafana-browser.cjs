/** Local Grafana visual checks. Credentials remain in the browser context only. */
const assert = require('node:assert/strict');
const {readFile, mkdir, writeFile} = require('node:fs/promises');
const path = require('node:path');
const {createRequire} = require('node:module');

const root = path.resolve(__dirname, '../../..');
const {chromium} = createRequire(path.join(root, 'web/package.json'))('playwright');
const base = process.env.GRAFANA_TEST_URL || 'http://127.0.0.1:53001';
const baseUrl = new URL(base);
assert(['127.0.0.1', 'localhost', '[::1]'].includes(baseUrl.hostname), 'A loopback Grafana URL is required');
assert(!baseUrl.username && !baseUrl.password, 'Do not place credentials in the URL');
const output = path.resolve(root, process.env.GRAFANA_TEST_OUTPUT_DIR || 'artifacts/operations-20260918/grafana');
const relativeOutput = path.relative(path.join(root,'artifacts'),output);
assert(!path.isAbsolute(relativeOutput) && !relativeOutput.split(path.sep).includes('..'),
  'Grafana test output must remain within workspace artifacts');
const dashboards = [
  {uid:'msp-overview', name:'overview'}, {uid:'msp-service', name:'spring'},
  {uid:'msp-infra', name:'aws'}, {uid:'msp-db', name:'db'},
  {uid:'msp-db-workload', name:'db-workload'}, {uid:'msp-db-replication', name:'db-replication'},
];
const panelSelector = 'section[data-testid^="data-testid Panel header "]';
const evidence = {generatedAt:new Date().toISOString(), source:'live-local-grafana', pages:[], checks:{}, errors:[]};

function safeError(error) {
  return String(error?.message || error)
    .replace(/(?:cookie|authorization):[^\r\n]*/gi, '[credential header redacted]')
    .replace(/grafana_session(?:_expiry)?=[^;\s]+/gi, '[session redacted]')
    .replace(/https?:\/\/\S+/g, '[URL]').slice(0, 400);
}

function trackQueries(page) {
  const tracker = {pending:new Set(), responses:[], jobs:new Set(), scriptErrors:[]};
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/ds/query') tracker.pending.add(request);
  });
  page.on('requestfailed', request => {
    if (!tracker.pending.has(request)) return;
    tracker.pending.delete(request);
    tracker.responses.push({status:0, error:safeError(request.failure()?.errorText || 'Query transport failure')});
  });
  page.on('response', response => {
    if (new URL(response.url()).pathname !== '/api/ds/query') return;
    const job = (async () => {
      try {
        const payload = await response.json();
        const results = Object.entries(payload.results || {}).map(([refId, result]) => ({
          refId, status:result.status || response.status(), frames:(result.frames || []).length,
          ...(result.error ? {error:safeError(result.error)} : {}),
        }));
        tracker.responses.push({status:response.status(), results});
      } catch (error) {
        tracker.responses.push({status:response.status(), error:safeError(error)});
      } finally {
        tracker.pending.delete(response.request());
      }
    })();
    tracker.jobs.add(job);
    job.finally(() => tracker.jobs.delete(job));
  });
  page.on('pageerror', error => tracker.scriptErrors.push(safeError(error)));
  return tracker;
}

async function waitForDashboard(page, tracker, expectedPanels) {
  await page.locator(panelSelector).first().waitFor({timeout:45000});
  await page.waitForFunction(() => !document.querySelector('[aria-label="Panel loading bar"]'), null, {timeout:45000});
  const deadline = Date.now() + 45000;
  while (tracker.pending.size || tracker.jobs.size || !tracker.responses.length) {
    if (Date.now() > deadline) throw new Error('Grafana query completion timed out');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(count => document.querySelectorAll('section[data-testid^="data-testid Panel header "]').length >= count,
    expectedPanels, {timeout:10000});
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function geometry(page) {
  return page.evaluate(selector => {
    const rounded = value => Math.round(value * 10) / 10;
    const bounds = node => {
      const r = node.getBoundingClientRect();
      return {left:rounded(r.left), top:rounded(r.top), right:rounded(r.right), bottom:rounded(r.bottom),
        width:rounded(r.width), height:rounded(r.height)};
    };
    const overlap = (a, b) => Math.min(a.right,b.right) - Math.max(a.left,b.left) > 1
      && Math.min(a.bottom,b.bottom) - Math.max(a.top,b.top) > 1;
    const sections = [...document.querySelectorAll(selector)].filter(node => node.getBoundingClientRect().height > 0);
    const panels = sections.map(section => ({title:section.getAttribute('data-testid').replace('data-testid Panel header ', ''), ...bounds(section)}));
    const panelOverlaps = [];
    for (let i=0;i<panels.length;i++) for (let j=i+1;j<panels.length;j++) {
      if (overlap(panels[i],panels[j])) panelOverlaps.push([panels[i].title,panels[j].title]);
    }
    const outsideViewport = panels.filter(p => p.left < -1 || p.top < -1 || p.right > innerWidth + 1 || p.bottom > innerHeight + 1);
    const clippedTitles = sections.flatMap(section => [...section.querySelectorAll('h2')]
      .filter(node => node.scrollWidth > node.clientWidth + 1)
      .map(node => node.textContent.trim()));
    const canvasTextIssues = [];
    const canvasText = [];
    for (let index=0;index<sections.length;index++) {
      const section = sections[index];
      const content = section.querySelector('[data-testid="data-testid panel content"]');
      if (!content) continue;
      const contentBounds = bounds(content);
      const text = [...content.querySelectorAll('span')]
        .filter(node => !node.children.length && node.textContent.trim() && getComputedStyle(node).display === 'table-cell')
        .map(node => {
          const range = document.createRange();
          range.selectNodeContents(node);
          const r = range.getBoundingClientRect();
          return {text:node.textContent.trim(), left:r.left, top:r.top, right:r.right, bottom:r.bottom,
            fontSize:getComputedStyle(node).fontSize, color:getComputedStyle(node).color};
        });
      for (const item of text) {
        if (item.left < contentBounds.left - 1 || item.right > contentBounds.right + 1
          || item.top < contentBounds.top - 1 || item.bottom > contentBounds.bottom + 1) {
          canvasTextIssues.push({panel:panels[index].title, problem:'text outside content', text:item.text});
        }
      }
      for (let i=0;i<text.length;i++) for (let j=i+1;j<text.length;j++) {
        if (overlap(text[i],text[j])) canvasTextIssues.push({panel:panels[index].title, problem:'text overlap', text:[text[i].text,text[j].text]});
      }
      if (text.length) canvasText.push({panel:panels[index].title, items:text.map(({text,fontSize,color}) => ({text,fontSize,color}))});
    }
    return {viewport:{width:innerWidth,height:innerHeight}, theme:document.body.classList.contains('theme-light') ? 'light' : 'dark',
      panels, panelOverlaps, outsideViewport, clippedTitles, canvasTextIssues, canvasText,
      fieldBindingErrors:[...document.querySelectorAll('span')].filter(node => !node.children.length
        && /^(Field not found|Double click to set field)$/.test(node.textContent.trim())).map(node => node.textContent.trim()),
      horizontalOverflow:document.documentElement.scrollWidth > innerWidth + 1};
  }, panelSelector);
}

async function capture(context, dashboard, theme, viewport, expectedPanels) {
  const page = await context.newPage();
  const tracker = trackQueries(page);
  const result = {dashboard:dashboard.name, uid:dashboard.uid, theme, ...viewport, status:'FAIL'};
  try {
    await page.setViewportSize(viewport);
    await page.goto(`/d/${dashboard.uid}?var-customer=exchange&var-environment=local-lab&from=now-1h&to=now&refresh=&theme=${theme}`);
    await waitForDashboard(page, tracker, expectedPanels);
    result.geometry = await geometry(page);
    result.queries = tracker.responses;
    result.scriptErrors = tracker.scriptErrors;
    await page.screenshot({path:path.join(output,`${dashboard.name}-${theme}-${viewport.width}.png`), fullPage:false});
    const checks = result.geometry;
    const queryErrors = tracker.responses.filter(item => item.status < 200 || item.status >= 300 || item.error
      || item.results?.some(query => query.error || query.status >= 400));
    const issues = [];
    if (checks.theme !== theme) issues.push('Wrong theme');
    if (checks.panels.length !== expectedPanels) issues.push(`Panel count ${checks.panels.length}/${expectedPanels}`);
    if (checks.panelOverlaps.length) issues.push('Overlapping panels');
    if (checks.outsideViewport.length) issues.push('Panels outside viewport');
    if (checks.clippedTitles.length) issues.push('Clipped panel titles');
    if (checks.canvasTextIssues.length) issues.push('Canvas text clipping or overlap');
    if (checks.fieldBindingErrors.length) issues.push('Canvas field binding error');
    if (checks.horizontalOverflow) issues.push('Horizontal document overflow');
    if (queryErrors.length) issues.push('Data source query errors');
    if (tracker.scriptErrors.length) issues.push('Browser script errors');
    result.issues = issues;
    result.status = issues.length ? 'FAIL' : 'PASS';
  } catch (error) {
    result.failure = safeError(error);
    result.queries = tracker.responses;
    await page.screenshot({path:path.join(output,`${dashboard.name}-${theme}-${viewport.width}-failure.png`), fullPage:false}).catch(() => {});
  } finally {
    evidence.pages.push(result);
    console.log(`${dashboard.name} ${theme} ${viewport.width}x${viewport.height}: ${result.status}${result.issues?.length ? ' (' + result.issues.join(', ') + ')' : ''}`);
    await page.close();
  }
}

async function themeLinks(context, expectedPanels) {
  const page = await context.newPage();
  const tracker = trackQueries(page);
  try {
    await page.goto('/d/msp-overview?var-customer=exchange&var-environment=local-lab&from=now-1h&to=now&refresh=&theme=dark');
    await waitForDashboard(page, tracker, expectedPanels);
    for (const theme of ['light','dark']) {
      const links = page.locator(`a[href*="theme=${theme}"]`);
      let clicked = false;
      let themedPage;
      for (let index=0;index<await links.count();index++) {
        if (await links.nth(index).isVisible()) {
          const popup = page.waitForEvent('popup');
          await links.nth(index).click();
          themedPage = await popup;
          clicked = true;
          break;
        }
      }
      assert(clicked, `Visible ${theme} theme link required`);
      await themedPage.waitForFunction(value => document.body.classList.contains(`theme-${value}`), theme, {timeout:30000});
      const current = new URL(themedPage.url());
      assert(current.pathname.startsWith('/d/msp-overview'), 'Theme link must retain the dashboard');
      assert.equal(current.searchParams.get('var-customer'), 'exchange');
      assert.equal(current.searchParams.get('var-environment'), 'local-lab');
      await themedPage.close();
    }
    evidence.checks.themeLinks = {status:'PASS', scopesPreserved:true, themes:['light','dark']};
  } catch (error) {
    evidence.checks.themeLinks = {status:'FAIL', failure:safeError(error)};
    await page.screenshot({path:path.join(output,'theme-link-failure.png'), fullPage:false}).catch(() => {});
  } finally {
    await page.close();
  }
}

async function run() {
  await mkdir(output, {recursive:true});
  const password = (await readFile(process.env.GRAFANA_TEST_PASSWORD_FILE
    || path.join(root,'deploy/observability/.state/grafana-password'), 'utf8')).trim();
  const browser = await chromium.launch({headless:true,
    channel:process.env.GRAFANA_TEST_BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined)});
  try {
    const context = await browser.newContext({baseURL:base, viewport:{width:1440,height:900}, reducedMotion:'reduce'});
    const login = await context.request.post('/login', {data:{user:process.env.GRAFANA_TEST_USER || 'operator', password}});
    assert.equal(login.status(), 200, 'Grafana login failed');
    evidence.checks.login = {status:'PASS'};
    const expected = {};
    for (const dashboard of dashboards) {
      const response = await context.request.get(`/api/dashboards/uid/${dashboard.uid}`);
      assert.equal(response.status(), 200, `Missing dashboard ${dashboard.uid}`);
      const model = (await response.json()).dashboard;
      expected[dashboard.uid] = model.panels.filter(panel => panel.type !== 'row').length;
      assert(expected[dashboard.uid] > 0, 'Dashboard must contain panels');
    }
    for (const viewport of [{width:1440,height:900},{width:1920,height:1080}]) {
      for (const theme of ['dark','light']) {
        for (const dashboard of dashboards) await capture(context, dashboard, theme, viewport, expected[dashboard.uid]);
      }
    }
    await themeLinks(context, expected['msp-overview']);
    evidence.status = evidence.pages.every(page => page.status === 'PASS')
      && Object.values(evidence.checks).every(check => check.status === 'PASS') ? 'PASS' : 'FAIL';
  } catch (error) {
    evidence.status = 'FAIL';
    evidence.errors.push(safeError(error));
  } finally {
    await writeFile(path.join(output,'browser-check.json'), JSON.stringify(evidence,null,2) + '\n');
    await browser.close();
  }
  console.log(`Grafana browser verification: ${evidence.status}`);
  if (evidence.status !== 'PASS') process.exitCode = 1;
}

run().catch(error => {console.error(safeError(error)); process.exitCode = 1;});
