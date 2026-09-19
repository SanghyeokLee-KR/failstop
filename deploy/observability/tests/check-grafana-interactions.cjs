/** Native Grafana interactions and browser-only Canvas status fixtures. */
const assert = require('node:assert/strict');
const {readFile, mkdir, writeFile} = require('node:fs/promises');
const path = require('node:path');
const {createRequire} = require('node:module');

const root = path.resolve(__dirname, '../../..');
const {chromium} = createRequire(path.join(root, 'web/package.json'))('playwright');
const base = process.env.GRAFANA_TEST_URL || 'http://127.0.0.1:53001';
assert(['127.0.0.1','localhost','[::1]'].includes(new URL(base).hostname), 'Loopback Grafana required');
const output = path.resolve(root, process.env.GRAFANA_TEST_OUTPUT_DIR || 'artifacts/operations-20260918/grafana');
const relativeOutput = path.relative(path.join(root,'artifacts'),output);
assert(!path.isAbsolute(relativeOutput) && !relativeOutput.split(path.sep).includes('..'),
  'Grafana test output must remain within workspace artifacts');
const dashboards = ['msp-overview','msp-service','msp-infra','msp-db','msp-db-workload','msp-db-replication'];
const states = new Set(['edge','cloudfront','alb','spring','primary','standby','replication']);
const evidence = {generatedAt:new Date().toISOString(), checks:[], actualServiceStopped:false, teamsMessagesSent:0};

function safeError(error) {
  return String(error?.message || error)
    .replace(/(?:cookie|authorization):[^\r\n]*/gi,'[credential header redacted]')
    .replace(/grafana_session(?:_expiry)?=[^;\s]+/gi,'[session redacted]')
    .replace(/https?:\/\/\S+/g,'[URL]').slice(0,500);
}

async function checked(name, callback) {
  try {
    const details = await callback();
    evidence.checks.push({name,status:'PASS',...details});
    console.log(`${name}: PASS`);
  } catch (error) {
    evidence.checks.push({name,status:'FAIL',error:safeError(error)});
    console.log(`${name}: FAIL (${safeError(error)})`);
  }
}

async function settled(page) {
  await page.locator('section[data-testid^="data-testid Panel header "]').first().waitFor({timeout:45000});
  await page.waitForFunction(() => !document.querySelector('[aria-label="Panel loading bar"]'), null, {timeout:45000});
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

function fixtureFrame(query, value) {
  return {schema:{refId:query.refId,meta:{type:'numeric-multi',typeVersion:[0,1],custom:{resultType:'vector'}},
    fields:[{name:'Time',type:'time',config:{interval:15000}},
      {name:'Value',type:'number',labels:{},config:{displayNameFromDS:query.legendFormat}}]},
    data:{values:[[Date.now()],[value]]}};
}

async function canvasFixtures(context, uid, title, theme) {
  const dashboard = await (await context.request.get(`/api/dashboards/uid/${uid}`)).json();
  const canvas = dashboard.dashboard.panels.find(panel => panel.type === 'canvas' && panel.title === title);
  assert(canvas, `Canvas panel required: ${uid} / ${title}`);
  const fields = new Set(canvas.targets.map(target => target.legendFormat));
  const expectedStates = [...fields].filter(field => states.has(field)).length;
  const expectedIcons = canvas.options.root.elements.filter(element => element.type === 'icon'
    && fields.has(element.config.fill.field)).length;
  const page = await context.newPage();
  let mode = 'healthy';
  let count = 0;
  const errors = [];
  await page.route('**/api/ds/query*', async route => {
    const request = route.request().postDataJSON();
    const isCanvas = request.queries?.some(query => query.legendFormat === (fields.has('wal_gap') ? 'wal_gap' : 'edge'));
    if (!isCanvas) return route.continue();
    try {
      const response = await route.fetch();
      assert.equal(response.status(),200,'Live Canvas query must succeed before applying fixture');
      const body = await response.json();
      for (const query of request.queries) {
        const result = body.results[query.refId];
        assert(result && !result.error, 'Query result required');
        result.frames = mode === 'missing' ? [] : [fixtureFrame(query, states.has(query.legendFormat) && mode === 'healthy' ? 1 : 0)];
      }
      await route.fulfill({response,json:body});
      count++;
    } catch (error) {
      errors.push(safeError(error));
      await route.abort();
    }
  });
  try {
    await page.goto(`/d/${uid}?var-customer=exchange&var-environment=local-lab&from=now-1h&to=now&refresh=&theme=${theme}`);
    const panel = page.locator(`section[data-testid="data-testid Panel header ${title}"]`);
    const outcomes = [];
    for (const nextMode of ['healthy','failed','missing']) {
      if (nextMode !== 'healthy') {
        mode = nextMode;
        const previous = count;
        await page.locator('[data-testid="data-testid RefreshPicker run button"]').click();
        const deadline = Date.now() + 30000;
        while (count === previous && !errors.length && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve,100));
        assert(count > previous,'Fixture refresh must complete');
      }
      const firstDeadline = Date.now() + 30000;
      while (!count && !errors.length && Date.now() < firstDeadline) await new Promise(resolve => setTimeout(resolve,100));
      await settled(page);
      assert(count > 0 && !errors.length, errors.join('; ') || 'Canvas fixture was not consumed');
      await panel.locator('svg[viewBox]').first().waitFor();
      const values = await panel.evaluate(node => ({
        text:node.innerText,
        states:[...node.querySelectorAll('span')].filter(span => !span.children.length
          && /^(● 정상|▲ 장애|미수집)$/.test(span.textContent.trim()))
          .map(span => ({text:span.textContent.trim(),color:getComputedStyle(span).color})),
        icons:[...node.querySelectorAll('[data-testid="data-testid panel content"] svg[viewBox]')]
          .map(svg => getComputedStyle(svg).fill),
      }));
      const rgb = color => color.match(/\d+/g)?.slice(0,3).map(Number) || [];
      const isRed = color => {const [r,g,b] = rgb(color);return r > 180 && g < 110 && b < 140;};
      const isGreen = color => {const [r,g,b] = rgb(color);return g > r * 1.2 && g > b * 1.2;};
      const isGray = color => rgb(color).join(',') === '120,131,150';
      if (nextMode === 'healthy') {
        assert.equal(values.states.filter(item => item.text === '● 정상').length, expectedStates);
        assert.equal(values.icons.filter(isGreen).length, expectedIcons);
        assert(!values.text.includes('▲ 장애'),'Numeric zero must not inherit the failed-state mapping');
        if (fields.has('wal_gap')) assert(/0\s*B/.test(values.text), 'WAL zero must retain its byte unit');
      } else if (nextMode === 'failed') {
        assert(!values.text.includes('정상'),'Failed fixture must replace every previous healthy status');
        assert.equal(values.states.filter(item => item.text === '▲ 장애').length, expectedStates);
        assert.equal(values.icons.filter(isRed).length, expectedIcons);
        if (fields.has('wal_gap')) assert(/0\s*B/.test(values.text),'WAL is a measurement, independent of the failed DB status');
      } else {
        assert(!/정상|장애|Field not found/.test(values.text),'Missing fixture must not retain healthy or failed claims');
        assert(values.states.length >= expectedStates);
        assert(values.states.every(item => item.text === '미수집'));
        assert(values.icons.every(isGray));
      }
      if (nextMode !== 'healthy') await page.screenshot({path:path.join(output,`${uid}-${theme}-${nextMode}-browser-fixture.png`),fullPage:false});
      outcomes.push({state:nextMode,stateLabels:values.states,iconColors:values.icons});
    }
    return {browserResponseOnly:true,theme,uid,outcomes};
  } finally {
    await page.close();
  }
}

async function missingDatabaseMetrics(context, uid, theme) {
  const response = await context.request.get(`/api/dashboards/uid/${uid}`);
  assert.equal(response.status(),200);
  const model = (await response.json()).dashboard;
  const measuredPanels = model.panels.filter(panel => ['stat','timeseries','table'].includes(panel.type)
    && panel.targets?.some(target => !target.hide));
  assert(measuredPanels.length > 0,'Measured database panels required');
  const page = await context.newPage();
  let intercepted = 0;
  let pending = 0;
  const errors = [];
  await page.route('**/api/ds/query*', async route => {
    pending++;
    try {
      const live = await route.fetch();
      assert.equal(live.status(),200,'Live query must succeed before applying missing-data fixture');
      const body = await live.json();
      for (const result of Object.values(body.results || {})) {
        assert(!result.error,'Live query syntax and data source must remain valid');
        result.frames = [];
      }
      await route.fulfill({response:live,json:body});
      intercepted++;
    } catch (error) {
      errors.push(safeError(error));
      await route.abort();
    } finally {
      pending--;
    }
  });
  try {
    await page.goto(`/d/${uid}?var-customer=exchange&var-environment=local-lab&refresh=&theme=${theme}`);
    // Panel shells can render before their initial query requests. A missing
    // loading bar alone does not prove that the response fixture was consumed.
    const deadline = Date.now() + 45000;
    while ((!intercepted || pending) && !errors.length && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve,100));
    }
    await settled(page);
    assert(intercepted > 0 && pending === 0 && !errors.length,
      errors.join('; ') || 'Missing-data fixture must be consumed');
    const panels = [];
    for (const modelPanel of measuredPanels) {
      const content = page.locator(`[data-viz-panel-key="panel-${modelPanel.id}"]`)
        .locator('[data-testid="data-testid panel content"]');
      await content.getByText(/^(No data|데이터 없음|미수집)$/).first().waitFor({timeout:10000});
      const text = (await content.innerText()).trim();
      assert(!/정상|▲ 장애/.test(text),`${modelPanel.title}: missing data must not imply health or failure`);
      assert(!/^0(?:[\s.%]|$)/m.test(text),`${modelPanel.title}: missing data must not become numeric zero`);
      panels.push({id:modelPanel.id,title:modelPanel.title,text});
    }
    assert(!errors.length,errors.join('; '));
    await page.screenshot({path:path.join(output,`${uid}-${theme}-missing-metrics-browser-fixture.png`),fullPage:false});
    return {uid,theme,browserResponseOnly:true,panels};
  } finally {
    await page.close();
  }
}

async function run() {
  await mkdir(output,{recursive:true});
  const password = (await readFile(process.env.GRAFANA_TEST_PASSWORD_FILE
    || path.join(root,'deploy/observability/.state/grafana-password'),'utf8')).trim();
  const browser = await chromium.launch({headless:true,
    channel:process.env.GRAFANA_TEST_BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined)});
  try {
    const context = await browser.newContext({baseURL:base,viewport:{width:1440,height:900},reducedMotion:'reduce'});
    const login = await context.request.post('/login',{data:{user:process.env.GRAFANA_TEST_USER || 'operator',password}});
    assert.equal(login.status(),200,'Grafana login failed');
    const page = await context.newPage();
    await checked('native-home-and-starred-sidebar',async () => {
      await page.goto('/');
      await settled(page);
      assert(new URL(page.url()).pathname.startsWith('/d/msp-overview'),'Native home must open the service dashboard');
      const starsResponse = await context.request.get('/api/user/stars');
      assert.equal(starsResponse.status(),200);
      const starred = await starsResponse.json();
      for (const uid of dashboards) {
        const response = await context.request.get(`/api/dashboards/uid/${uid}`);
        assert.equal(response.status(),200);
        assert(starred.includes(uid),`${uid} is not starred`);
      }
      const collapsed = page.getByRole('button',{name:/^(섹션 펼치기: 별표 표시됨|Expand section: Starred)$/});
      if (await collapsed.count()) await collapsed.click();
      const sidebar = await page.locator('a[href*="/d/msp-"]').evaluateAll(nodes => nodes
        .filter(node => node.getBoundingClientRect().left < 220 && node.getBoundingClientRect().height > 0)
        .map(node => ({title:node.textContent.trim(),uid:node.getAttribute('href').split('/')[2]})));
      for (const uid of dashboards) assert(sidebar.some(item => item.uid === uid),`${uid} absent from expanded sidebar`);
      await page.screenshot({path:path.join(output,'native-home-starred-sidebar.png'),fullPage:false});
      return {homeDashboard:'msp-overview',sidebar};
    });
    await checked('native-dashboard-links-and-time-range',async () => {
      await page.goto('/d/msp-overview?var-customer=exchange&var-environment=local-lab&refresh=');
      await settled(page);
      await page.locator('[data-testid="data-testid TimePicker Open Button"]').click();
      await page.getByText(/^(최근 6시간|Last 6 hours)$/).click();
      await page.waitForFunction(() => new URL(location.href).searchParams.get('from') === 'now-6h');
      for (const uid of [...dashboards.slice(1), dashboards[0]]) {
        const link = page.locator(`a[href^="/d/${uid}?"]`).first();
        await link.click();
        await page.waitForFunction(value => location.pathname.startsWith(`/d/${value}`),uid);
        await settled(page);
        const current = new URL(page.url());
        assert.equal(current.searchParams.get('from'),'now-6h');
        assert.equal(current.searchParams.get('var-customer'),'exchange');
        assert.equal(current.searchParams.get('var-environment'),'local-lab');
      }
      await page.screenshot({path:path.join(output,'native-time-range-six-hours.png'),fullPage:false});
      return {timeRange:'now-6h',dashboardLinks:dashboards.length,scopePreserved:true};
    });
    await checked('native-alert-rules-and-alertmanager',async () => {
      const rules = await context.request.get('/api/prometheus/prometheus/api/v1/rules');
      assert.equal(rules.status(),200);
      const ruleData = await rules.json();
      assert.equal(ruleData.status,'success');
      const groups = ruleData.data?.groups || [];
      assert(groups.length > 0,'Prometheus rule groups required');
      const manager = await context.request.get('/api/alertmanager/alertmanager/api/v2/status');
      assert.equal(manager.status(),200);
      const managerData = await manager.json();
      assert(managerData.versionInfo?.version,'Alertmanager version required');
      await page.goto('/alerting/list');
      await page.getByRole('main').waitFor({timeout:30000});
      assert(!(await page.locator('body').innerText()).includes('Page not found'));
      return {ruleGroups:groups.length,rules:groups.reduce((count,group) => count + group.rules.length,0),alertmanagerStatus:200};
    });
    await checked('native-profile-theme-options',async () => {
      const before = await (await context.request.get('/api/user/preferences')).json();
      await page.goto('/profile');
      const select = page.locator('#shared-preferences-theme-select');
      await select.waitFor({timeout:45000});
      await select.click();
      const options = await page.locator('[role="option"]').allTextContents();
      assert(options.some(option => /Light|라이트|밝은/i.test(option)),'Native light theme option required');
      assert(options.some(option => /Dark|다크|어두운/i.test(option)),'Native dark theme option required');
      await page.getByRole('option',{name:/Light|라이트|밝은/i}).first().click();
      assert(await page.getByRole('button',{name:/기본 설정 저장|Save preferences/i}).isEnabled());
      await page.goto('/d/msp-overview?theme=light&var-customer=exchange&var-environment=local-lab&refresh=');
      await settled(page);
      assert(await page.locator('body').evaluate(node => node.classList.contains('theme-light')));
      const after = await (await context.request.get('/api/user/preferences')).json();
      assert.equal(after.theme,before.theme,'Unsaved profile selection must not change the operator preference');
      return {profileOptions:options,selectedLight:true,renderedLight:true,preferenceUnchanged:true};
    });
    await page.close();
    for (const theme of ['dark','light']) {
      await checked(`service-canvas-${theme}-fixtures`,() => canvasFixtures(context,'msp-overview','서비스 경로 · 계층별 상태',theme));
      await checked(`database-canvas-${theme}-fixtures`,() => canvasFixtures(context,'msp-db','DB 복제 구성 · 현재 역할',theme));
      for (const uid of ['msp-db-workload','msp-db-replication']) {
        await checked(`${uid}-${theme}-missing-metrics`,() => missingDatabaseMetrics(context,uid,theme));
      }
    }
    await context.close();
    evidence.status = evidence.checks.every(check => check.status === 'PASS') ? 'PASS' : 'FAIL';
  } catch (error) {
    evidence.status = 'FAIL';
    evidence.failure = safeError(error);
  } finally {
    await writeFile(path.join(output,'interactions-check.json'),JSON.stringify(evidence,null,2) + '\n');
    await browser.close();
  }
  console.log(`Grafana interactions: ${evidence.status}`);
  if (evidence.status !== 'PASS') process.exitCode = 1;
}

run().catch(error => {console.error(safeError(error));process.exitCode = 1;});
