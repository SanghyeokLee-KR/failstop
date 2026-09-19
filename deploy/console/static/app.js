import {icon} from './icons.js';
import {escapeHtml as esc, safeHref, metricValue, statusFromMetric, statusForTargets, selectDbSeries, number, dateTime} from './domain.js';
import {chart, clearCharts, wireCharts} from './charts.js';

const routes = [
  ['overview','01. 서비스 관제','grid','관제'], ['spring','02. 트래픽 · Spring','activity','관제'],
  ['aws','03. AWS · 서버','cloud','관제'], ['db','04. DB · 복구','database','관제'],
  ['customers','고객 전체 현황','users','관제'], ['alerts','알림 현황','bell','운영'],
  ['events','이벤트 이력','history','운영'], ['reports','운영 보고서','report','운영'],
  ['settings','설정','settings','관리'], ['dashboards','대시보드 관리','grid','관리'],
  ['datasources','데이터 소스','layers','관리'], ['users','사용자 관리','users','관리'],
];
const routeMap = Object.fromEntries(routes.map((route) => [route[0], route]));
const dashboardViews = ['overview', 'spring', 'aws', 'db'];
const stateLabels = {good:'정상', warning:'주의', danger:'장애', unknown:'자료 없음'};
const stateIcons = {good:'check', warning:'warning', danger:'error', unknown:'unknown'};
const durations = [[3600,'1시간'],[21600,'6시간'],[86400,'1일'],[604800,'7일']];
const app = document.querySelector('#app');
const modal = document.querySelector('#modal');
const panels = new Map();
let toastTimer;
let requestSequence = 0;
let controller;
let refreshTimer;
let preferences = readPreferences();
let state = {
  route: routeMap[location.hash.slice(1)] ? location.hash.slice(1) : 'overview',
  boot:null, snapshot:null, reports:[], events:[], admin:null,
  customer:'', environment:'', service:'', instance:'',
  range:preferences.range, refresh:preferences.refresh, loading:true, error:'', auxiliaryError:'',
  tableSearch:'', alertFilter:'all', reportFilter:'all', eventFilter:'all',
};

function readPreferences() {
  const defaults = {theme:'dark', refresh:15, range:3600};
  try {
    const saved = JSON.parse(localStorage.getItem('msp.console.preferences') || '{}');
    return {theme:['dark','light'].includes(saved.theme) ? saved.theme : defaults.theme,
      refresh:[0,15,30,60].includes(Number(saved.refresh)) ? Number(saved.refresh) : defaults.refresh,
      range:durationsSafe(saved.range) ? Number(saved.range) : defaults.range};
  } catch { return defaults; }
}

function durationsSafe(value) { return [3600,21600,86400,604800].includes(Number(value)); }

function savePreferences(next) {
  preferences = {...preferences,...next};
  try { localStorage.setItem('msp.console.preferences', JSON.stringify(preferences)); }
  catch { toast('브라우저 저장소 사용 불가 · 현재 세션에만 적용'); }
}

function setTheme(theme) {
  document.documentElement.dataset.theme = theme;
  savePreferences({theme});
}
setTheme(preferences.theme);

async function api(path, options = {}) {
  const response = await fetch(path, {credentials:'same-origin', ...options,
    headers:{Accept:'application/json', ...(options.body ? {'Content-Type':'application/json'} : {}), ...options.headers}});
  let data;
  try { data = await response.json(); } catch { data = {}; }
  if (!response.ok) {
    const error = new Error(data.error || data.message || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

function toast(message) {
  const element = document.querySelector('#toast');
  element.textContent = message;
  element.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => element.classList.remove('visible'), 4200);
}

function badge(status, text) {
  const safeStatus = stateLabels[status] ? status : 'unknown';
  return `<span class="state-badge ${safeStatus}">${icon(stateIcons[safeStatus])}${esc(text || stateLabels[safeStatus])}</span>`;
}
function m(id) { return state.snapshot?.metrics?.[id] || {state:'missing',series:[]}; }
function value(id, operation = 'sum') { return metricValue(m(id), operation); }
function format(id, unit = '', operation = 'sum') { return number(value(id, operation), unit); }
function external(url, title, name = 'external', className = 'button') {
  const href = safeHref(url);
  return href ? `<a class="${className}" href="${esc(href)}" target="_blank" rel="noopener noreferrer">${icon(name)}${esc(title)}</a>` : `<span class="${className} disabled" title="연결 미등록">${icon(name)}${esc(title)} · 미연결</span>`;
}
function targetLabel(labels = {}) { return labels.node || labels.instance || labels.name || labels.role || '대상 미상'; }
function activeAlerts() { return (state.snapshot?.alerts || []).filter((alert) => alert.state === 'firing'); }
function alertsAvailable() { return Boolean(state.snapshot && !state.error && state.snapshot.sources?.some((source)=>source.name === 'alerts' && source.state === 'ok')); }
function serviceStatus(id,job) { return statusForTargets(m(id),state.snapshot?.targets || [],job); }
function alertSeverity(severity) { return /critical|error|P1/i.test(severity || '') ? 'danger' : /warning|P2/i.test(severity || '') ? 'warning' : 'unknown'; }
function overallStatus() {
  if (state.error) return 'unknown';
  if (activeAlerts().some((alert) => alertSeverity(alert.severity) === 'danger')) return 'danger';
  const checks = [['app_serviceable','exchange-api'],['db_up','postgres'],['probe_up','blackbox-http']].map(([id,job]) => serviceStatus(id,job));
  if (checks.includes('danger')) return 'danger';
  if ((state.snapshot?.sources || []).some((source) => source.state === 'error')) return 'unknown';
  if (activeAlerts().length) return 'warning';
  if (checks.includes('unknown')) return 'unknown';
  return 'good';
}
function scope() { return state.boot?.scopes?.find((item) => item.customer === state.customer && item.environment === state.environment); }
function scopedQuery(extra = {}) {
  return new URLSearchParams({customer:state.customer, environment:state.environment, ...extra}).toString();
}

function shell() {
  const user = state.boot.user;
  const current = routeMap[state.route];
  const alerts = activeAlerts().length;
  const scopes = state.boot.scopes || [];
  const customers = [...new Set(scopes.map((item) => item.customer))];
  const environments = scopes.filter((item) => item.customer === state.customer).map((item) => item.environment);
  const filters = dashboardViews.includes(state.route) || state.route === 'alerts';
  const sourceErrors = (state.snapshot?.sources || []).filter((source) => source.state === 'error');
  return `<div class="shell"><header class="topbar">
    <a href="#overview" class="wordmark">${icon('brand')}<span>MSP 관제센터</span></a>
    <div class="breadcrumb"><span>대시보드</span>${icon('chevron')}<span>${esc(state.customer || '고객 미등록')} / ${esc(state.environment || '환경 미등록')}</span>${icon('chevron')}<strong>${esc(current[1].replace(/^\d+\. /,''))}</strong></div>
    <button class="top-search" data-action="search">${icon('search')}<span>고객, 서버, 지표 검색</span><kbd>Ctrl K</kbd></button>
    <div class="top-actions"><button class="icon-button" data-action="theme" aria-label="${preferences.theme === 'dark' ? '라이트' : '다크'} 모드 전환" title="${preferences.theme === 'dark' ? '라이트' : '다크'} 모드">${icon(preferences.theme === 'dark' ? 'sun' : 'moon')}</button>
    <a class="icon-button" href="#alerts" aria-label="진행 중 알림 ${alerts}건" title="알림 현황">${icon('bell')}${alerts ? `<span class="notification-count">${alerts}</span>` : ''}</a>
    <button class="user-button" data-action="account" aria-label="계정 메뉴">${icon('user')}<span>${esc(user.name || user.login || '관제 운영자')}</span>${icon('down')}</button></div>
    ${state.loading ? '<div class="loading-line" role="progressbar" aria-label="지표 조회 중"></div>' : ''}
  </header><aside class="sidebar" aria-label="주 메뉴">${['관제','운영','관리'].map((group) => `<nav class="nav-group" aria-label="${group}">${group !== '관제' ? `<div class="nav-heading">${group}</div>` : ''}${routes.filter((route) => route[3] === group).map(([id,title,name]) => `<a class="nav-item ${state.route === id ? 'active' : ''}" href="#${id}" ${state.route === id ? 'aria-current="page"' : ''}>${icon(name)}<span>${title}</span>${id === 'alerts' && alerts ? `<span class="nav-count">${alerts}</span>` : ''}</a>`).join('')}</nav>`).join('')}
    <div class="customer-nav"><div class="nav-heading">등록 고객</div>${customers.map((customer) => `<a class="customer-item" href="#overview" data-customer="${esc(customer)}">${icon('globe')}${esc(customer)}</a>`).join('') || '<div class="customer-item muted">등록 고객 없음</div>'}</div>
    <div class="sidebar-footer">${icon('brand')}<div><strong>Operations Console</strong>고객 서비스 관제</div></div>
  </aside><main id="main" class="main" tabindex="-1" data-route="${state.route}" aria-busy="${state.loading}"><section class="filters" aria-label="조회 조건">
    <label class="filter-control"><span>고객사</span><select id="customer-filter" aria-label="고객사">${customers.map((item) => option(item,item,state.customer)).join('') || option('','미등록','')}</select></label>
    <label class="filter-control"><span>환경</span><select id="environment-filter" aria-label="환경">${environments.map((item) => option(item,item,state.environment)).join('') || option('','미등록','')}</select></label>
    ${filters ? `<label class="filter-control"><span>서비스</span><select id="service-filter" aria-label="서비스">${option('','전체',state.service)}${(scope()?.services || []).map((item) => option(item,serviceName(item),state.service)).join('')}</select></label>` : ''}
    ${dashboardViews.includes(state.route) ? `<label class="filter-control"><span>대상</span><select id="instance-filter" aria-label="인스턴스">${option('','전체',state.instance)}${(scope()?.instances || []).map((item) => option(item,item,state.instance)).join('')}</select></label>` : ''}
    <span class="filter-spacer"></span>${filters ? `<div class="time-options" aria-label="조회 시간 범위">${durations.map(([range,title]) => `<button data-range="${range}" class="${state.range === range ? 'active' : ''}" aria-pressed="${state.range === range}">${title}</button>`).join('')}</div>` : ''}
    <label class="filter-control"><select id="refresh-filter" aria-label="자동 갱신 주기">${[[0,'자동 갱신 끔'],[15,'15초'],[30,'30초'],[60,'1분']].map(([val,title]) => option(val,title,state.refresh)).join('')}</select></label>
    <button class="icon-button" data-action="refresh" title="새로고침" aria-label="새로고침">${icon('refresh')}</button>
  </section><div class="content">${state.error ? `<div class="stale-banner danger" role="alert">${icon('error')}조회 중단 · ${esc(state.error)}${state.snapshot ? ` · 마지막 성공 ${dateTime(state.snapshot.generatedAt)} KST` : ''}<button class="button small" data-action="refresh">다시 조회</button></div>` : sourceErrors.length ? `<div class="stale-banner" role="alert">${icon('warning')}일부 수집 실패 · ${sourceErrors.map((source)=>esc(source.message || source.name)).join(' / ')}</div>` : ''}<div id="view">${renderView()}</div>
    <footer class="footer"><span class="footer-state ${state.error ? 'danger' : 'subtle'}">${icon(state.error ? 'error' : state.loading ? 'refresh' : 'check')}${state.error ? '갱신 실패 · 이전 표본' : state.loading ? '지표 조회 중' : 'Prometheus 실측 자료'}${state.snapshot ? ` <span class="muted">· ${esc(state.customer)} / ${esc(state.environment)}</span>` : ''}</span><span>최근 조회 <strong>${dateTime(state.snapshot?.generatedAt, true)} KST</strong> <span class="muted"> &nbsp;|&nbsp; ${state.refresh ? `${state.refresh}초 자동 갱신` : '수동 갱신'}</span></span></footer>
  </div></main></div>`;
}

function option(value, title, selected) { return `<option value="${esc(value)}" ${String(value) === String(selected) ? 'selected' : ''}>${esc(title)}</option>`; }
function serviceName(name) { return {apps:'Spring',databases:'PostgreSQL',probes:'외부 접속',hosts:'고객 서버'}[name] || name; }
function render() {
  if (!state.boot) return;
  clearCharts();
  panels.clear();
  app.innerHTML = shell();
  wireCharts(app);
  document.title = `${routeMap[state.route][1].replace(/^\d+\. /,'')} · MSP 관제센터`;
  wireControls();
}

function panel(title, body, span = 3, options = {}) {
  const key = `panel-${panels.size}`;
  if (options.chart) panels.set(key, {title, ...options.chart});
  return `<section class="panel span-${span}"><div class="panel-head"><h2>${esc(title)}</h2>${options.note ? `<small title="${esc(options.note)}">${esc(options.note)}</small>` : ''}<div class="panel-actions">${options.action || ''}${options.chart ? `<button class="icon-button" data-expand="${key}" aria-label="${esc(title)} 확대" title="확대">${icon('expand')}</button>` : ''}</div></div><div class="panel-body ${options.chart ? 'chart-body' : ''} ${options.className || ''}">${body}</div>${options.foot ? `<div class="panel-foot">${options.foot}</div>` : ''}</section>`;
}
function chartPanel(title, id, unit = '', span = 3, options = {}) {
  const configuration = {unit, start:state.snapshot?.start,end:state.snapshot?.end,...options};
  return panel(title, chart(m(id), configuration), span, {chart:{id,configuration}, note:options.note});
}
function combinedPanel(title, ids, names, unit = '', span = 3, options = {}) {
  const series = ids.flatMap((id,index) => m(id).series.map((item) => ({...item, labels:{...item.labels,legend:names[index]}})));
  const metric = {state:ids.some((id) => m(id).state === 'ok') ? 'ok' : 'missing',series};
  const configuration = {unit, start:state.snapshot?.start,end:state.snapshot?.end,...options};
  return panel(title, chart(metric, configuration), span, {chart:{metric,configuration},note:options.note});
}
function kpi(title, id, unit = '', {operation='sum',tone='',foot='',raw} = {}) {
  const metric = m(id);
  const val = raw === undefined ? value(id,operation) : raw;
  const status = val === null ? 'unknown' : tone;
  const partial = metric.series?.some((series) => series.value === null);
  return `<div class="kpi"><div class="kpi-label">${esc(title)}</div><div class="kpi-value ${status}">${esc(typeof val === 'string' ? val : number(val,unit))}</div><div class="kpi-foot">${esc(val === null ? metric.state === 'error' ? '수집 오류' : '자료 없음' : partial ? '부분 수집 · 미수신 대상 포함' : foot)}</div>${metric.series?.length ? `<div class="kpi-spark">${chart(metric,{spark:true,start:state.snapshot?.start,end:state.snapshot?.end})}</div>` : ''}</div>`;
}
function hero(title, name, status, kpis, note) {
  if (state.error) status = 'unknown';
  if (status !== 'danger' && state.snapshot?.sources?.some((source)=>source.state === 'error')) status = 'unknown';
  const statusCopy = {good:'수집된 핵심 지표 정상',warning:'운영 확인 필요',danger:'장애 지표 감지',unknown:'판정 자료 부족'};
  const goodCopy = {overview:'요청 수용 · DB 연결 · 접속 확인',spring:'등록 인스턴스 요청 수용 확인',db:'수집된 PostgreSQL 연결 확인',aws:'ALB 대상 그룹 상태 기준'};
  return `<section class="hero span-12"><div class="hero-identity"><div class="service-mark ${name}">${icon(name)}</div><div><div class="hero-title"><h1>${esc(title)}</h1>${badge(status)}</div><div class="hero-detail"><span>${esc(state.customer)}</span><span class="separator">/</span><span>${esc(state.environment)}</span><span class="separator">|</span><span>${esc(note || '고객 서비스 관제')}</span></div></div></div><div class="hero-status ${status}">${icon(stateIcons[status])}<div><strong>${statusCopy[status]}</strong><small>${state.error ? '조회 실패 · 이전 표본 확인' : activeAlerts().length ? `진행 경보 ${activeAlerts().length}건 · 영향 확인` : status === 'good' ? goodCopy[state.route] : '연결 상태 및 수집 범위 확인'}</small></div></div>${kpis}</section>`;
}
function table(headers, rows, emptyMessage = '조회 결과 없음') {
  return `<table class="table"><thead><tr>${headers.map((header) => `<th>${esc(header)}</th>`).join('')}</tr></thead><tbody>${rows.length ? rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('') : `<tr><td colspan="${headers.length}"><div class="empty">${icon('unknown')}${esc(emptyMessage)}</div></td></tr>`}</tbody></table>`;
}
function empty(message, detail = '') { return `<div class="empty">${icon('unknown')}<span>${esc(message)}</span>${detail ? `<small>${esc(detail)}</small>` : ''}</div>`; }
function keyValues(rows) { return `<div class="key-values">${rows.map(([key,val]) => `<div class="key-value"><span>${esc(key)}</span><strong>${val}</strong></div>`).join('')}</div>`; }

function renderView() {
  if (dashboardViews.includes(state.route) && !state.snapshot && state.loading) return loadingDashboard();
  switch (state.route) {
    case 'overview':return overview();
    case 'spring':return spring();
    case 'db':return database();
    case 'aws':return aws();
    case 'customers':return customers();
    case 'alerts':return alertsPage();
    case 'events':return eventsPage();
    case 'reports':return reportsPage();
    case 'settings':return settingsPage();
    default:return adminPage();
  }
}
function loadingDashboard() {
  return `<div class="dashboard overview"><section class="hero span-12"><div class="hero-identity"><div class="service-mark">${icon('brand')}</div><div><h1>${esc(state.customer || '관제 대상')}</h1><p class="muted">지표 조회 중</p></div></div>${Array.from({length:4},() => '<div class="kpi"><div class="skeleton"></div><div class="skeleton large"></div></div>').join('')}</section>${Array.from({length:16},() => panel('지표 조회', '<div class="skeleton-chart"><div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div></div>',3)).join('')}</div>`;
}

function routeNode(title, name, status, metric, detail) {
  return `<div class="route-node ${status}">${icon(name)}<strong>${esc(title)}</strong>${badge(status)}<span class="route-value">${esc(metric)}</span><small>${esc(detail)}</small></div>`;
}
function serviceFlow() {
  const arrow = `<div class="route-arrow">${icon('arrow')}</div>`;
  return `<div class="route-flow">${[
    routeNode('외부 접속','globe',serviceStatus('probe_up','blackbox-http'),number(value('probe_up','min')),'HTTP 상태 확인'),
    routeNode('CloudFront · ALB','cloud','unknown','\u2014','AWS 상세 관제'),
    routeNode('Spring 서비스','spring',serviceStatus('app_serviceable','exchange-api'),format('rps','req/s'),'요청 수용 상태'),
    routeNode('Primary DB','database',dbStatus('primary'),dbMetric('primary','db_connections','개'),'쓰기 노드'),
    routeNode('Standby DB','database',dbStatus('standby'),format('wal_gap','bytes','max'),'복제 노드 · WAL 거리'),
  ].join(arrow)}</div>`;
}
function dbSeries(role, id) {
  return selectDbSeries(state.snapshot?.metrics || {},role,id);
}
function dbStatus(role) { return statusFromMetric({state:m('db_up').state,series:dbSeries(role,'db_up')}); }
function dbMetric(role,id,unit) { return number(metricValue({state:m(id).state,series:dbSeries(role,id)}),unit); }

function summaryPanel() {
  const targets = state.snapshot?.targets || [];
  const up = targets.filter((target) => target.health === 'up').length;
  const alertsKnown = alertsAvailable();
  return `<div class="summary-stats"><div class="summary-stat"><small>수집 응답</small><strong>${up}<span class="muted"> / ${targets.length}</span></strong><p>등록 고객 대상</p></div><div class="summary-stat"><small>진행 경보</small><strong class="${alertsKnown ? activeAlerts().length ? 'warning' : 'good' : 'unknown'}">${alertsKnown ? activeAlerts().length : '\u2014'}<small> 건</small></strong><p>${alertsKnown ? 'Prometheus 경보' : '경보 원본 조회 실패'}</p></div><div class="summary-stat"><small>미확인 경보</small><strong>${alertsKnown ? activeAlerts().filter((alert) => !alert.acknowledgement).length : '\u2014'}<small> 건</small></strong><p>운영자 확인 기준</p></div></div>`;
}
function availability() {
  const series = m('availability').series || [];
  if (!series.length) return empty('상태 이력 없음','수집 성공 여부 기준 · 서비스 SLA와 별도');
  const rows = series.slice(0,5).map((item) => {
    const points = item.points || [];
    return `<div class="timeline-row"><span title="${esc(targetLabel(item.labels))} · 현재 역할">${esc(observedRole(item.labels))}</span><div class="timeline-track" aria-label="${esc(targetLabel(item.labels))} 수집 상태">${points.map((point) => `<i class="${point[1] === null ? 'unknown' : point[1] > 0 ? 'good' : 'danger'}" title="${dateTime(point[0])} · ${point[1] === null ? '자료 없음' : point[1] > 0 ? '수집 성공' : '수집 실패'}"></i>`).join('')}</div></div>`;
  }).join('');
  return `<div class="timeline-list">${rows}<div class="timeline-caption"><span>${dateTime(state.snapshot?.start).slice(0,5)}</span><span>${dateTime(state.snapshot?.end).slice(0,5)}</span></div><div class="timeline-legend"><span><i style="background:var(--good)"></i>수집 성공</span><span><i style="background:var(--danger)"></i>수집 실패</span><span><i style="background:var(--unknown)"></i>자료 없음</span><span class="muted">서비스 SLA와 별도</span></div></div>`;
}
function observedRole(labels = {}) {
  if (labels.job === 'postgres') {
    const recovery = m('db_recovery').series.find((series)=>series.labels?.instance === labels.instance)?.value;
    return recovery === 0 ? 'Primary DB' : recovery === 1 ? 'Standby DB' : 'DB 역할 미확인';
  }
  if (labels.job === 'linux-host') return '고객 서버';
  return {app:'Spring 서비스',reader:'Spring 조회',probe:'외부 접속'}[labels.role] || labels.node || labels.job || '관측 대상';
}
function alertRows(limit = 5) {
  return (state.snapshot?.alerts || []).slice(0,limit).map((alert) => [esc(dateTime(alert.activeAt)), badge(alertSeverity(alert.severity), alert.severity === 'critical' ? '장애' : alert.severity === 'warning' ? '경고' : '정보'), esc(alert.labels?.role || targetLabel(alert.labels)), `<button class="text-link" data-alert="${esc(alert.id)}">${esc(alert.summary || alert.name)}</button>`,badge(alert.state === 'firing' ? 'danger' : 'warning',alert.state === 'firing' ? '발생' : '대기')]);
}
function eventRows(limit = 5) {
  return state.events.slice(0,limit).map((event) => [esc(dateTime(event.time || event.created)),badge(event.status === 'resolved' ? 'good' : alertSeverity(event.severity),eventType(event)),esc(event.target || event.targets?.join(', ') || '\u2014'),esc(event.title || event.summary || '운영 보고')]);
}
function eventType(event) { return event.type === 'daily' ? '일일 보고' : event.type === 'acknowledgement' ? '알림 확인' : event.status === 'resolved' ? '경보 해제' : '장애 보고'; }
function eventStatus(status) { return {firing:'발생',resolved:'해제',acknowledged:'확인 기록',sent:'전달 완료',pending:'대기',dead:'전달 실패',failed:'실패'}[status] || status || '\u2014'; }
function quickLinks() {
  const links = state.boot.links || {};
  return `<div class="quick-links"><a class="quick-link" href="#spring">${icon('activity')}트래픽 · Spring</a><a class="quick-link" href="#aws">${icon('cloud')}AWS · 서버</a><a class="quick-link" href="#db">${icon('database')}DB · 복구</a><a class="quick-link" href="#reports">${icon('report')}PDF · HTML 보고</a>${external(links.grafana,'Grafana 상세','grid','quick-link')}${external(links.prometheus,'Prometheus 질의','search','quick-link')}</div>`;
}
function overview() {
  return `<div class="dashboard overview">${hero(state.customer || '고객 서비스','users',overallStatus(),
    kpi('요청 처리율','rps','req/s',{foot:'Spring 업무 요청'}) + kpi('API 오류율 · 5xx','error_rate','%',{tone:value('error_rate') > 1 ? 'danger' : 'good',foot:'업무 요청 대비'}) + kpi('응답 지연 · P95','latency_p95','ms',{foot:'선택 구간'}) + kpi('DB 연결 사용률','db_utilization','%',{operation:'max',foot:'연결 한도 대비 최대'}),'서비스 상태 · 고객 범위')}
    ${panel('서비스 경로 · 연결 상태',serviceFlow(),7)}${panel('관제 상태 요약',summaryPanel(),5)}
    ${chartPanel('요청 처리율 · Spring','rps','req/s')}${combinedPanel('응답 지연 · 평균 / P95',['latency_mean','latency_p95'],['평균','P95'],'ms')}${chartPanel('DB 복제 · WAL 재생 거리','wal_gap','bytes')}${combinedPanel('Spring CPU · JVM 사용률',['app_cpu','app_memory'],['프로세스 CPU','JVM 사용 / 확보'],'%',3,{max:100})}
    ${panel('대상별 수집 상태 이력',availability(),6)}${panel('진행 경보',table(['발생 시각','등급','대상','내용','상태'],alertRows(4),alertsAvailable() ? '진행 경보 없음' : '경보 조회 미확인'),6,{action:'<a class="text-link" href="#alerts">전체 보기</a>'})}
    ${panel('최근 운영 이벤트',table(['시각','유형','대상','내용'],eventRows(4),state.auxiliaryError || '저장된 운영 이벤트 없음'),6,{action:'<a class="text-link" href="#events">전체 보기</a>'})}${panel('관제 바로가기',quickLinks(),6)}
  </div>`;
}

function instanceTable() {
  const instances = m('app_serviceable').series;
  const find = (id, instance) => m(id).series.find((item) => item.labels?.instance === instance)?.value;
  return table(['인스턴스','요청 수용','CPU','JVM 사용률','처리율'],instances.map((item) => {
    const instance = item.labels?.instance;
    return [esc(item.labels?.node || instance),badge(item.value === null ? 'unknown' : item.value > 0 ? 'good' : 'danger'),number(find('app_cpu',instance),'%'),number(find('app_memory',instance),'%'),number(find('instance_rps',instance),'req/s')];
  }),'Spring 대상 미등록');
}
function requestsTable(limit = 5) {
  return table(['메서드','요청 경로','요청 수'],[...m('requests_by_uri').series].sort((a,b) => (b.value || 0) - (a.value || 0)).slice(0,limit).map((item) => [esc(item.labels?.method || '\u2014'),esc(item.labels?.uri || '\u2014'),number(item.value,'',0)]),'선택 구간 요청 없음');
}
function collectionStatus() {
  const targets = (state.snapshot?.targets || []).filter((target) => target.labels?.service === 'apps' || target.labels?.job?.includes('spring') || target.labels?.job === 'exchange-api');
  return `<div class="status-list">${targets.map((target) => `<div class="status-row"><span class="status-label">${icon(target.health === 'up' ? 'check' : 'error',target.health === 'up' ? 'good' : 'danger')}${esc(target.labels?.role || 'Spring 지표')}</span>${badge(target.health === 'up' ? 'good' : 'danger',target.health === 'up' ? '수집 성공' : '수집 실패')}</div>`).join('')}${['app_serviceable','rps','latency_p95'].map((id) => `<div class="status-row"><span class="status-label">${esc({app_serviceable:'요청 수용 상태',rps:'HTTP 업무 요청',latency_p95:'응답 시간 분포'}[id])}</span>${badge(m(id).state === 'ok' ? 'good' : 'unknown',m(id).state === 'ok' ? '자료 수신' : '자료 없음')}</div>`).join('')}</div>`;
}
function spring() {
  const up = m('app_serviceable').series.filter((item) => item.value > 0).length;
  const count = m('app_serviceable').series.length;
  return `<div class="dashboard spring">${hero('Spring Boot','spring',serviceStatus('app_serviceable','exchange-api'),
    kpi('요청 수용 인스턴스','app_serviceable','',{raw:count ? `${up} / ${count}` : null,foot:'등록 인스턴스 기준'})+kpi('요청 처리율','rps','req/s',{foot:'업무 요청'})+kpi('오류율 · 5xx','error_rate','%',{tone:value('error_rate') > 1 ? 'danger' : 'good',foot:'업무 요청 대비'})+kpi('응답 지연 · P95','latency_p95','ms',{foot:'분포 기반'}),'요청 처리 · JVM · 인스턴스')}
    ${panel('서비스 아키텍처 · 연결 상태',serviceFlow(),6)}${panel('인스턴스 상태',instanceTable(),6,{note:'JVM: 확보 메모리 대비'})}
    ${chartPanel('요청 처리율 · 인스턴스','instance_rps','req/s')}${combinedPanel('응답 시간 · 평균 / P95 / P99',['latency_mean','latency_p95','latency_p99'],['평균','P95','P99'],'ms')}${chartPanel('HTTP 응답 코드','http_codes','req/s')}${chartPanel('오류율 · 5xx','error_rate','%',3,{threshold:1})}
    ${chartPanel('프로세스 CPU 사용률','app_cpu','%',3,{max:100})}${combinedPanel('JVM 메모리 · 사용 / 확보',['jvm_heap','jvm_nonheap'],['Heap','Non-heap'],'%',3,{max:100})}${chartPanel('JVM GC 시간 비율','gc_time','%')}${combinedPanel('스레드 · DB 활성 연결',['threads','hikari_active'],['스레드','Hikari 활성'],'개')}
    ${panel('요청 경로 · 상위 5개',requestsTable(),4)}${panel('최근 운영 이벤트',table(['시각','유형','대상','내용'],eventRows(4),state.auxiliaryError || '저장된 운영 이벤트 없음'),4)}${panel('수집 상태',collectionStatus(),2)}${panel('상세 조회',`<div class="quick-links" style="grid-template-columns:1fr">${external(state.boot.links?.grafana,'Grafana','grid','quick-link')}<a class="quick-link" href="#events">${icon('history')}이벤트 이력</a><a class="quick-link" href="#reports">${icon('report')}운영 보고서</a></div>`,2)}
  </div>`;
}

function dbTopology() {
  const node = (role,title,detail) => {
    const item = dbSeries(role,'db_up')[0];
    return `<div class="db-node"><div class="db-node-header">${icon('database')}<div class="db-node-title"><strong>${title}</strong><div class="node-meta">${esc(item?.labels?.node || item?.labels?.instance || '등록 자료 없음')}</div></div>${badge(dbStatus(role))}</div>${keyValues([['역할',detail],['현재 연결',dbMetric(role,'db_connections','개')],['연결 사용률',dbMetric(role,'db_utilization','%')]])}</div>`;
  };
  return `<div class="db-flow">${node('primary','Primary','쓰기')}<div class="replication-arrow"><span>Streaming Replication</span>${icon('arrow')}<strong>${format('wal_gap','bytes','max')}</strong><span>WAL 재생 거리</span><span class="muted">수동 승격 절차</span></div>${node('standby','Standby','복제')}</div>`;
}
function backupStatus() {
  const timestamp = value('backup_timestamp','max');
  const age = timestamp ? (Date.now() / 1000 - timestamp) / 3600 : null;
  const status = age === null ? 'unknown' : age > 26 ? 'warning' : 'good';
  return `<div class="status-list"><div class="status-row"><strong>최근 백업</strong>${badge(status,status === 'good' ? '최근 성공 확인' : status === 'warning' ? '26시간 초과' : '자료 없음')}</div>${keyValues([['최근 성공 시각',esc(dateTime(timestamp,true))],['경과 시간',age === null ? '\u2014' : `${number(age)}시간`],['복원 검증','<span class="muted">실행 기록 별도 확인</span>']])}<a class="text-link" href="#reports">백업 경보 · 운영 보고 확인 ${icon('chevron')}</a></div>`;
}
function runbook() {
  return `<div class="runbook">${['Primary 장애·복제 상태 확인','기존 Primary 격리 · 쓰기 차단','Standby 수동 승격 · 유실 범위 확인','애플리케이션 DB 연결 전환','요청·원장 검증 · 기존 노드 재편입'].map((text,index) => `<div class="runbook-step"><span class="step-number">${index+1}</span>${text}</div>`).join('')}</div>`;
}
function database() {
  return `<div class="dashboard db">${hero('PostgreSQL','database',serviceStatus('db_up','postgres'),
    kpi('DB 연결 사용률','db_utilization','%',{operation:'max',foot:'최대 사용률'})+kpi('WAL 재생 거리','wal_gap','bytes',{operation:'max',foot:'비동기 복제'})+kpi('활성 세션','db_active','개',{foot:'현재 DB 세션'})+kpi('복제 지연','replication_lag','s',{operation:'max',foot:'수신 표본 기준'}),'Primary · Standby · 수동 복구')}
    ${panel('DB 복제 구성도',dbTopology(),6)}${chartPanel('복제 · WAL 재생 거리','wal_gap','bytes',3,{threshold:104857600})}${chartPanel('DB 연결 사용률','db_utilization','%',3,{max:100,threshold:80})}
    ${chartPanel('트랜잭션 처리량','db_tps','TPS')}${chartPanel('활성 세션 수','db_active','개')}${chartPanel('전체 잠금 수','db_locks','개',3,{note:'잠금 대기와 구분'})}${chartPanel('DB 연결 수','db_connections','개')}
    ${chartPanel('WAL 생성률','wal_rate','B/s')}${chartPanel('복제 지연 시간','replication_lag','s',3,{threshold:5})}${panel('테이블 크기 · 상위 5개',table(['테이블','크기'],[...m('db_tables').series].sort((a,b)=>(b.value||0)-(a.value||0)).slice(0,5).map((item)=>[esc(item.labels?.relname || item.labels?.table || item.labels?.name || '테이블'),number(item.value,'bytes')]),'테이블 크기 수집 미연결'),3)}${panel('DB 연결 상태',`<div class="status-list"><div class="status-row"><span>Primary 연결</span>${badge(dbStatus('primary'))}</div><div class="status-row"><span>Standby 연결</span>${badge(dbStatus('standby'))}</div>${keyValues([['현재 연결 수',format('db_connections','개')],['WAL 재생 거리',format('wal_gap','bytes','max')],['복제 지연',format('replication_lag','s','max')]])}</div>`,3)}
    ${panel('최근 DB 경보',table(['시각','등급','대상','내용'],alertRows(4).map((row)=>row.slice(0,4)),alertsAvailable() ? '진행 경보 없음' : '경보 조회 미확인'),4)}${panel('백업 상태',backupStatus(),3)}${panel('DB 장애 · 복구 절차',runbook(),3,{action:'<button class="text-link" data-action="runbook" aria-label="DB 상세 복구 절차">상세 절차</button>'})}${panel('바로가기',`<div class="quick-links" style="grid-template-columns:1fr"><a class="quick-link" href="#reports">${icon('report')}백업 · 장애 보고</a><a class="quick-link" href="#alerts">${icon('bell')}경보 현황</a>${external(state.boot.links?.grafana,'DB 상세 지표','database','quick-link')}</div>`,2)}
  </div>`;
}

function hostTable() {
  const targets = (state.snapshot?.targets || []).filter((target) => target.labels?.service === 'hosts' || target.labels?.role?.includes('host'));
  return table(['서버','역할','가용 영역','수집','최근 응답'],targets.map((target)=>[esc(targetLabel(target.labels)),esc(target.labels?.role || '\u2014'),esc(target.labels?.availability_zone || target.labels?.az || '\u2014'),badge(target.health === 'up' ? 'good' : 'danger'),esc(dateTime(target.lastScrape))]),'고객 서버 수집 대상 미등록');
}
function awsConnections() {
  const groups = [['ALB 대상 그룹','정상·비정상 대상','aws_healthy_hosts'],['ALB 요청','처리량·응답 지연','aws_requests'],['조회 ASG','목표·가동 인스턴스','aws_asg_desired'],['EBS','볼륨 I/O 대기','aws_ebs_queue']];
  return `<div class="status-list">${groups.map(([name,note,id])=>`<div class="status-row"><span><strong>${name}</strong><small class="muted"> · ${note}</small></span>${badge(m(id).state === 'ok' ? 'good' : m(id).state === 'error' ? 'danger' : 'unknown',m(id).state === 'ok' ? '자료 수신' : m(id).state === 'error' ? '조회 오류' : '자료 없음')}</div>`).join('')}<a class="text-link" href="#datasources">CloudWatch 데이터 소스 ${icon('chevron')}</a></div>`;
}
function aws() {
  const healthy = value('aws_healthy_hosts');
  const unhealthy = value('aws_unhealthy_hosts');
  const status = unhealthy > 0 ? 'danger' : healthy > 0 && unhealthy === 0 ? 'good' : 'unknown';
  return `<div class="dashboard aws">${hero('AWS · 고객 서버','cloud',status,
    kpi('ALB 정상 대상','aws_healthy_hosts','대',{tone:'good',foot:'대상 그룹 최소'})+kpi('ALB 비정상 대상','aws_unhealthy_hosts','대',{tone:unhealthy > 0 ? 'danger' : 'good',foot:'대상 그룹 최대'})+kpi('조회 ASG 가동','aws_asg_inservice','대',{foot:'InService'})+kpi('조회 ASG 목표','aws_asg_desired','대',{foot:'Desired Capacity'}),'ALB · 조회 ASG · EC2 · EBS')}
    ${panel('고객 서버 인벤토리',hostTable(),7)}${panel('AWS 관측 연결',awsConnections(),5)}
    ${chartPanel('ALB 요청 수','aws_requests','건/분')}${chartPanel('ALB 대상 응답 · P95','aws_latency_p95','ms')}${chartPanel('ALB · 대상 서버 오류','aws_errors','건/분')}${combinedPanel('조회 ASG · 목표 / 가동',['aws_asg_desired','aws_asg_inservice'],['목표','가동'],'대')}
    ${chartPanel('호스트 CPU 사용률','host_cpu','%',3,{max:100,threshold:85})}${chartPanel('물리 메모리 사용률','host_memory','%',3,{max:100,threshold:90})}${chartPanel('디스크 사용률','host_disk','%',3,{max:100,threshold:85})}${chartPanel('inode 사용률','host_inode','%',3,{max:100,threshold:85})}
    ${chartPanel('네트워크 수신','host_network_rx','B/s')}${chartPanel('네트워크 송신','host_network_tx','B/s')}${chartPanel('디스크 I/O 사용률','host_io','%',3,{max:100})}${chartPanel('EBS I/O 대기','aws_ebs_queue','개')}
  </div>`;
}

function pageHeading(title, description, actions = '') {
  return `<div class="page-heading"><div><h1>${esc(title)}</h1><p>${esc(description)}</p></div><div class="page-actions">${actions}</div></div>`;
}
function pageTable(title, headers, rows, {emptyMessage='조회 결과 없음',filter=''} = {}) {
  return `<section class="panel full-panel"><div class="panel-head"><h2>${esc(title)}</h2><span class="count-label">${rows.length}건</span><div class="panel-actions"><div class="table-filter">${filter}<input id="table-search" type="search" aria-label="목록 검색" placeholder="대상, 이름, 내용 검색" value="${esc(state.tableSearch)}">${icon('search')}</div></div></div><div class="panel-body">${table(headers,rows,emptyMessage)}</div></section>`;
}
function matches(...fields) {
  return fields.join(' ').toLocaleLowerCase().includes(state.tableSearch.trim().toLocaleLowerCase());
}
function customers() {
  const scopes = state.boot.scopes || [];
  return `${pageHeading('고객 전체 현황','Prometheus 고객·환경 라벨 기준의 등록 대상')}<div class="page-grid">${scopes.map((item) => {
    const selected = item.customer === state.customer && item.environment === state.environment;
    return `<section class="panel span-4 customer-card"><div class="customer-card-head">${icon('users')}<div><h2>${esc(item.customer)}</h2><span class="muted">${esc(item.environment)}</span></div>${selected ? badge(overallStatus()) : badge('unknown','조회 전')}</div>${keyValues([['등록 서비스',esc(item.services.map(serviceName).join(' · '))],['등록 대상',`${item.instances.length}개`],['조회 상태',selected ? '현재 선택' : '선택 후 실측 조회']])}<button class="button" data-scope-customer="${esc(item.customer)}" data-scope-environment="${esc(item.environment)}">서비스 관제 ${icon('arrow')}</button></section>`;
  }).join('') || `<section class="panel span-12">${empty('등록 고객 없음','Prometheus 대상의 customer·environment 라벨 확인')}</section>`}</div>`;
}
function alertsPage() {
  const alerts = (state.snapshot?.alerts || []).filter((alert) => matches(alert.name,alert.summary,alert.labels?.instance,alert.labels?.role) && (state.alertFilter === 'all' || state.alertFilter === 'unacknowledged' && !alert.acknowledgement || alert.state === state.alertFilter));
  const sourceFailed = !alertsAvailable();
  return `${pageHeading('알림 현황','진행 경보 · 운영자 확인 이력',external(state.boot.links?.alertmanager,'Alertmanager','external'))}
    ${sourceFailed ? '<div class="stale-banner" role="alert">경보 원본 조회 실패 · 진행 경보 건수 판정 불가</div>' : ''}
    ${pageTable('현재 경보',['등급','상태','발생 시각 · KST','대상','경보','운영자 확인','상세'],alerts.map((alert)=>[badge(alertSeverity(alert.severity),alert.severity || '정보'),badge(alert.state === 'firing' ? 'danger' : 'warning',alert.state === 'firing' ? '발생' : '대기'),esc(dateTime(alert.activeAt,true)),esc(targetLabel(alert.labels)),`<span class="reports-title">${esc(alert.summary || alert.name)}</span><br><small class="muted">${esc(alert.name)}</small>`,alert.acknowledgement ? `<span class="good">${esc(alert.acknowledgement.by)}</span><br><small class="muted">${dateTime(alert.acknowledgement.at,true)}</small>` : '<span class="warning">미확인</span>',`<button class="button small" data-alert="${esc(alert.id)}">${icon('info')}상세</button>`]),{emptyMessage:sourceFailed ? '경보 원본 조회 실패' : '조건에 해당하는 진행 경보 없음',filter:`<select id="alert-filter" aria-label="경보 상태">${[['all','전체'],['firing','발생'],['pending','대기'],['unacknowledged','미확인']].map(([val,title])=>option(val,title,state.alertFilter)).join('')}</select>`})}`;
}
function eventsPage() {
  const events = state.events.filter((event)=>matches(event.title,event.summary,event.target,event.targets?.join(' ')) && (state.eventFilter === 'all' || event.type === state.eventFilter || event.status === state.eventFilter));
  return `${pageHeading('이벤트 이력','저장된 일일·장애 보고와 경보 발생·해제 이력')}${state.auxiliaryError ? `<div class="stale-banner" role="alert">${esc(state.auxiliaryError)}</div>` : ''}${pageTable('운영 이벤트',['시각 · KST','유형','등급','대상','이벤트','상태','원본'],events.map((event)=>[esc(dateTime(event.time || event.created,true)),esc(eventType(event)),badge(event.status === 'resolved' ? 'good' : alertSeverity(event.severity),event.severity || '정보'),esc(event.target || event.targets?.join(', ') || '\u2014'),`<span class="reports-title">${esc(event.title || event.summary || '운영 보고')}</span>`,esc(eventStatus(event.status)),`<button class="button small" data-event="${esc(event.id)}">상세</button>`]),{emptyMessage:state.auxiliaryError || '저장된 운영 이벤트 없음',filter:`<select id="event-filter" aria-label="이벤트 유형">${[['all','전체'],['incident','장애 보고'],['daily','일일 보고'],['resolved','경보 해제'],['acknowledgement','알림 확인']].map(([val,title])=>option(val,title,state.eventFilter)).join('')}</select>`})}`;
}
function reportsPage() {
  const reports = state.reports.filter((report)=>matches(report.title,report.summary,report.targets?.join(' '),report.id) && (state.reportFilter === 'all' || report.kind === state.reportFilter));
  return `${pageHeading('운영 보고서','Teams 전달 이력 · PDF / HTML 원본')}${state.auxiliaryError ? `<div class="stale-banner" role="alert">${esc(state.auxiliaryError)}</div>` : ''}${pageTable('보고서 목록',['작성 시각 · KST','유형','우선순위','보고서','대상','전달','파일'],reports.map((report)=>[esc(dateTime(report.created,true)),esc(report.kind === 'daily' ? '일일 보고' : '장애 보고'),badge(report.alertState === 'resolved' ? 'good' : alertSeverity(report.severity),report.severity || '\u2014'),`<button class="text-link reports-title" data-report="${esc(report.id)}">${esc(report.title || report.summary || '운영 보고서')}</button>`,esc((report.targets || []).join(', ') || '\u2014'),badge(report.status === 'sent' ? 'good' : report.status === 'dead' || report.status === 'failed' ? 'danger' : 'warning',{sent:'전달 완료',pending:'전달 대기',dead:'전달 실패',failed:'전달 실패'}[report.status] || report.status || '확인 필요'),`<div class="report-links">${report.links?.pdf ? external(report.links.pdf,'PDF','download','button small') : '<span class="muted">PDF 없음</span>'}${report.links?.html ? external(report.links.html,'HTML','external','button small') : ''}</div>`]),{emptyMessage:state.auxiliaryError || '저장된 보고서 없음',filter:`<select id="report-filter" aria-label="보고서 유형">${[['all','전체'],['incident','장애 보고'],['daily','일일 보고']].map(([val,title])=>option(val,title,state.reportFilter)).join('')}</select>`})}`;
}
function settingsPage() {
  return `${pageHeading('설정','표시 환경 · 갱신 주기 · 계정 권한')}<form id="settings-form" class="page-grid"><section class="panel settings-card span-6"><h2>화면 설정</h2><p>이 브라우저의 관제 화면에 적용</p><div class="form-field"><span>색상 모드</span><div class="theme-choice"><button type="button" class="${preferences.theme === 'dark' ? 'active' : ''}" data-theme="dark">${icon('moon')}다크 모드</button><button type="button" class="${preferences.theme === 'light' ? 'active' : ''}" data-theme="light">${icon('sun')}라이트 모드</button></div></div><label class="form-field">기본 조회 범위<select name="range">${durations.map(([value,title])=>option(value,title,preferences.range)).join('')}</select></label><label class="form-field">자동 갱신 주기<select name="refresh">${[[0,'자동 갱신 끔'],[15,'15초'],[30,'30초'],[60,'1분']].map(([value,title])=>option(value,title,preferences.refresh)).join('')}</select><small>화면이 숨겨진 동안 자동 조회 중단</small></label><div class="settings-actions"><button type="button" class="button" data-action="reset-preferences">기본값 복원</button><button class="button primary" type="submit">설정 저장</button></div></section><section class="panel settings-card span-6"><h2>계정 및 운영 범위</h2><p>Grafana 인증 연계</p>${keyValues([['계정',esc(state.boot.user.login)],['표시 이름',esc(state.boot.user.name || '\u2014')],['권한',esc(state.boot.user.role || 'Viewer')],['관리자',state.boot.user.isAdmin ? badge('good','권한 있음') : badge('unknown','권한 없음')],['보고 시각','매일 09:00 KST'],['표시 시간대','Asia/Seoul · KST']])}<div class="settings-note" style="margin-top:20px">경보 확인은 운영자 기록이며 경보 해제와 별도. 인프라 변경·DB 승격·자동 복구 기능 미제공. 권한과 데이터 소스 변경은 Grafana 관리 화면에서 수행.</div><div class="settings-actions" style="margin-top:18px">${external(state.boot.links?.grafana ? `${state.boot.links.grafana.replace(/\/$/,'')}/profile` : '', 'Grafana 계정 설정','user')}<button type="button" class="button" data-action="logout">${icon('logout')}로그아웃</button></div></section></form>`;
}
function adminPage() {
  const admin = state.admin;
  const route = state.route;
  const grafana = safeHref(state.boot.links?.grafana)?.replace(/\/$/,'');
  const config = {
    dashboards:{title:'대시보드 관리',description:'등록된 Grafana 대시보드 · 상세 편집',link:`${grafana}/dashboards`,label:'Grafana 대시보드',headers:['이름','UID','상세 조회'],rows:admin?.dashboards || []},
    datasources:{title:'데이터 소스',description:'관측 데이터 소스 연결 · 접근 권한',link:`${grafana}/connections/datasources`,label:'Grafana 데이터 소스',headers:['이름','종류','상태','기본 소스','상세 관리'],rows:admin?.datasources || []},
    users:{title:'사용자 관리',description:'Grafana 조직 사용자 · 관제 권한',link:`${grafana}/org/users`,label:'Grafana 사용자 관리',headers:['계정','이름','권한'],rows:admin?.users || []},
  }[route];
  const rows = config.rows.filter((item)=>matches(item.title,item.name,item.login,item.uid,item.type));
  let cells;
  if (route === 'dashboards') cells = rows.map((item)=>[esc(item.title),`<span class="mono">${esc(item.uid)}</span>`,external(item.url?.startsWith('/') ? `${grafana}${item.url}` : item.url,'열기','external','button small')]);
  if (route === 'datasources') cells = rows.map((item)=>[esc(item.name),esc(item.type),badge(item.state === 'ok' ? 'good' : item.state === 'error' ? 'danger' : 'unknown',item.state === 'ok' ? '연결 확인' : item.state === 'error' ? '연결 오류' : '상태 미확인'),item.isDefault ? badge('good','기본') : '\u2014',external(grafana ? `${grafana}/connections/datasources/edit/${encodeURIComponent(item.uid)}` : '','설정','settings','button small')]);
  if (route === 'users') cells = rows.map((item)=>[esc(item.login),esc(item.name || '\u2014'),badge(item.role === 'Admin' ? 'warning' : 'unknown',item.role || 'Viewer')]);
  const denied = admin?.state === 'forbidden' || admin?.state === 'error' || admin?.errors?.includes(route);
  return `${pageHeading(config.title,config.description,grafana ? external(config.link,config.label) : '')}${denied ? `<div class="stale-banner">${icon('lock')}${esc(admin.message || '목록 조회 실패 · Grafana 권한 또는 연결 확인')}</div>` : route === 'users' && !state.boot.user.isAdmin ? '<div class="stale-banner">현재 계정만 표시 · 조직 사용자 관리는 관리자 권한 필요</div>' : ''}${pageTable(config.title,config.headers,cells || [],{emptyMessage:state.loading ? '관리 목록 조회 중' : denied ? '목록 조회 제한' : '등록 항목 없음'})}`;
}

function wireControls(pageOnly = false) {
  if (!pageOnly) {
    document.querySelector('#customer-filter')?.addEventListener('change',(event)=>changeScope(event.target.value));
    document.querySelector('#environment-filter')?.addEventListener('change',(event)=>changeScope(state.customer,event.target.value));
    for (const field of ['service','instance']) document.querySelector(`#${field}-filter`)?.addEventListener('change',(event)=>{
      state[field] = event.target.value;
      state.snapshot = null;
      load();
    });
    document.querySelector('#refresh-filter')?.addEventListener('change',(event)=>{
      state.refresh = Number(event.target.value);
      savePreferences({refresh:state.refresh});
      schedule(); render();
    });
  }
  document.querySelector('#table-search')?.addEventListener('input',(event)=>{
    const position = event.target.selectionStart;
    state.tableSearch = event.target.value;
    const view = document.querySelector('#view');
    view.innerHTML = renderView();
    wireControls(true);
    const input = document.querySelector('#table-search');
    input?.focus();
    if (input && input.type !== 'search') input.setSelectionRange(position,position);
  });
  for (const field of ['alert','event','report']) document.querySelector(`#${field}-filter`)?.addEventListener('change',(event)=>{
    state[`${field}Filter`] = event.target.value;
    render();
  });
  document.querySelector('#settings-form')?.addEventListener('submit',(event)=>{
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const range = Number(data.get('range'));
    const refresh = Number(data.get('refresh'));
    if (!durationsSafe(range) || ![0,15,30,60].includes(refresh)) return;
    savePreferences({range,refresh});
    state.range = range; state.refresh = refresh;
    schedule(); toast('화면 설정 저장 완료'); render();
  });
}

async function changeScope(customer,environment) {
  const available = state.boot.scopes.filter((item)=>item.customer === customer);
  if (!available.length) return;
  state.customer = customer;
  state.environment = available.find((item)=>item.environment === environment)?.environment || available[0].environment;
  state.service = ''; state.instance = ''; state.snapshot = null; state.reports = []; state.events = [];
  state.error = ''; state.auxiliaryError = '';
  await load();
}

async function load() {
  if (!state.boot) return;
  controller?.abort();
  controller = new AbortController();
  const currentController = controller;
  const request = ++requestSequence;
  state.loading = true;
  document.querySelector('#main')?.setAttribute('aria-busy','true');
  if (!state.snapshot) render();
  const signal = currentController.signal;
  const selectedView = dashboardViews.includes(state.route) ? state.route : 'overview';
  const jobs = [api(`/api/metrics?${scopedQuery({view:selectedView,range:state.range,service:state.service,instance:state.instance})}`,{signal})];
  const includeReports = state.route === 'reports';
  const includeEvents = dashboardViews.includes(state.route) || state.route === 'events';
  const includeAdmin = ['dashboards','datasources','users'].includes(state.route);
  if (includeReports) jobs.push(api(`/api/reports?${scopedQuery()}`,{signal}));
  if (includeEvents) jobs.push(api(`/api/events?${scopedQuery()}`,{signal}));
  if (includeAdmin) jobs.push(api('/api/admin',{signal}));
  const results = await Promise.allSettled(jobs);
  if (request !== requestSequence || signal.aborted) return;
  if (results.some((result)=>result.status === 'rejected' && result.reason.status === 401)) {
    state.boot = null; state.snapshot = null; state.reports = []; state.events = []; state.admin = null; state.auxiliaryError = '';
    clearInterval(refreshTimer); loginScreen('로그인 세션 만료 · 다시 로그인'); return;
  }
  state.loading = false;
  const first = results[0];
  if (first.status === 'fulfilled') {
    state.snapshot = first.value; state.error = '';
  } else { state.error = first.reason.message || '관측 서비스 연결 실패'; }
  let index = 1;
  state.auxiliaryError = '';
  if (includeReports) {
    const result = results[index++];
    if (result.status === 'fulfilled') {
      state.reports = result.value.reports || [];
      if (result.value.state !== 'ok') state.auxiliaryError = result.value.message || '보고 서비스 조회 제한';
    } else { state.reports = []; state.auxiliaryError = `보고 서비스 조회 실패 · ${result.reason.message}`; }
  }
  if (includeEvents) {
    const result = results[index++];
    if (result.status === 'fulfilled') {
      state.events = result.value.events || [];
      if (result.value.state !== 'ok') state.auxiliaryError = result.value.message || '이벤트 이력 조회 제한';
    } else { state.events = []; state.auxiliaryError = `이벤트 이력 조회 실패 · ${result.reason.message}`; }
  }
  if (includeAdmin) {
    const result = results[index];
    state.admin = result.status === 'fulfilled' ? result.value : {state:result.reason.status === 403 ? 'forbidden' : 'error',message:result.reason.message};
  }
  render();
}

function schedule() {
  clearInterval(refreshTimer);
  if (!state.refresh) return;
  refreshTimer = setInterval(()=>{
    if (!document.hidden && !modal.open && !['INPUT','TEXTAREA','SELECT'].includes(document.activeElement?.tagName) && !state.loading) load();
  },state.refresh * 1000);
}

function openDialog(title,body,actions = '') {
  modal.className = '';
  modal.setAttribute('aria-labelledby','modal-title');
  modal.removeAttribute('aria-label');
  modal.innerHTML = `<div class="dialog-head"><h2 id="modal-title">${esc(title)}</h2><button class="icon-button" data-action="close-modal" aria-label="닫기">${icon('close')}</button></div><div class="dialog-body">${body}</div>${actions ? `<div class="dialog-actions">${actions}</div>` : ''}`;
  if (!modal.open) modal.showModal();
}

function showAlert(id) {
  const alert = (state.snapshot?.alerts || []).find((item)=>item.id === id);
  if (!alert) return toast('현재 조회 범위에 없는 경보');
  const acknowledged = alert.acknowledgement;
  const canAcknowledge = ['Admin','Editor'].includes(state.boot.user.role);
  openDialog(alert.summary || alert.name,`${keyValues([['경보 이름',esc(alert.name)],['대상',esc(targetLabel(alert.labels))],['상태',badge(alert.state === 'firing' ? 'danger' : 'warning',alert.state === 'firing' ? '발생' : '대기')],['발생 시각',esc(dateTime(alert.activeAt,true))],['확인 담당',acknowledged ? `${esc(acknowledged.by)} · ${dateTime(acknowledged.at,true)}` : '미확인']])}<p>${esc(alert.description || alert.summary || '')}</p>${acknowledged ? `<p>확인 메모: ${esc(acknowledged.note || '없음')}</p>` : ''}${canAcknowledge ? '<label class="form-field">운영자 확인 메모<textarea id="ack-note" maxlength="500" placeholder="초동 확인 사항, 담당자 전달 내용"></textarea><small>경보 확인 기록만 저장. 실제 경보 해제와 별도.</small></label>' : '<p>확인 기록은 Editor 또는 Admin 권한 필요</p>'}`, `<button class="button" data-action="close-modal">닫기</button>${canAcknowledge ? `<button class="button primary" data-ack="${esc(id)}">${icon('check')}확인 기록</button>` : ''}`);
}
function showReport(id) {
  const report = state.reports.find((item)=>item.id === id);
  if (!report) return;
  openDialog(report.title || '운영 보고서',`${keyValues([['보고 ID',`<span class="mono">${esc(report.id)}</span>`],['작성 시각',esc(dateTime(report.created,true))],['전달 시각',esc(dateTime(report.sent,true))],['대상',esc(report.targets?.join(', ') || '\u2014')],['전달 상태',esc(report.status || '\u2014')]])}<p>${esc(report.summary || '요약 없음')}</p>`,`${report.links?.pdf ? external(report.links.pdf,'PDF 보고서','download') : ''}${report.links?.html ? external(report.links.html,'HTML 보고서','external') : ''}`);
}
function showEvent(id) {
  const event = state.events.find((item)=>item.id === id);
  if (!event) return;
  openDialog(event.title || '운영 이벤트',`${keyValues([['시각',esc(dateTime(event.time || event.created,true))],['유형',esc(eventType(event))],['대상',esc(event.target || event.targets?.join(', ') || '\u2014')],['상태',esc(eventStatus(event.status))]])}<p>${esc(event.summary || '')}</p>`, `${event.links?.pdf ? external(event.links.pdf,'PDF 원본','download') : ''}${event.links?.html ? external(event.links.html,'HTML 원본','external') : ''}`);
}
function openSearch() {
  const items = routes.map(([id,title,name,group])=>({title,name,group,route:id}));
  for (const target of state.snapshot?.targets || []) items.push({title:targetLabel(target.labels),name:'server',group:target.labels?.role || '관측 대상',route:target.labels?.service === 'databases' ? 'db' : target.labels?.service === 'apps' ? 'spring' : 'aws',instance:target.labels?.instance});
  openDialog('관제 검색','');
  modal.innerHTML = `<div class="dialog-search">${icon('search')}<input id="global-search" type="search" placeholder="화면, 고객 서버, 관측 대상 검색" aria-label="전체 검색"><button class="icon-button" data-action="close-modal" aria-label="닫기">${icon('close')}</button></div><div id="global-results" class="search-results"></div>`;
  modal.setAttribute('aria-label','관제 검색');
  modal.removeAttribute('aria-labelledby');
  const input = modal.querySelector('#global-search');
  const output = modal.querySelector('#global-results');
  const update = () => {
    const query = input.value.trim().toLocaleLowerCase();
    output.innerHTML = items.filter((item)=>`${item.title} ${item.group}`.toLocaleLowerCase().includes(query)).map((item)=>`<button class="search-result" data-search-route="${esc(item.route)}" ${item.instance ? `data-search-instance="${esc(item.instance)}"` : ''}>${icon(item.name)}${esc(item.title)}<small>${esc(item.group)}</small></button>`).join('') || empty('검색 결과 없음');
  };
  input.addEventListener('input',update); update(); input.focus();
}

document.addEventListener('click', async (event) => {
  const action = event.target.closest('[data-action]')?.dataset.action;
  const rangeButton = event.target.closest('[data-range]');
  if (rangeButton) {
    state.range = Number(rangeButton.dataset.range); state.snapshot = null; await load(); return;
  }
  const scopeButton = event.target.closest('[data-scope-customer]');
  if (scopeButton) {
    state.route = 'overview'; location.hash = 'overview'; await changeScope(scopeButton.dataset.scopeCustomer,scopeButton.dataset.scopeEnvironment); return;
  }
  const customerLink = event.target.closest('[data-customer]');
  if (customerLink && customerLink.dataset.customer !== state.customer) await changeScope(customerLink.dataset.customer);
  const themeButton = event.target.closest('[data-theme]');
  if (themeButton?.tagName === 'BUTTON') { setTheme(themeButton.dataset.theme); render(); }
  const panelButton = event.target.closest('[data-expand]');
  if (panelButton) {
    const entry = panels.get(panelButton.dataset.expand);
    if (entry) { openDialog(entry.title,`<div class="expanded-chart">${chart(entry.metric || m(entry.id),entry.configuration)}</div>`); modal.classList.add('expanded-dialog'); wireCharts(modal); }
  }
  const alertButton = event.target.closest('[data-alert]');
  if (alertButton) showAlert(alertButton.dataset.alert);
  const reportButton = event.target.closest('[data-report]');
  if (reportButton) showReport(reportButton.dataset.report);
  const eventButton = event.target.closest('[data-event]');
  if (eventButton) showEvent(eventButton.dataset.event);
  const searchButton = event.target.closest('[data-search-route]');
  if (searchButton) {
    modal.close();
    if (searchButton.dataset.searchInstance) state.instance = searchButton.dataset.searchInstance;
    if (state.route === searchButton.dataset.searchRoute) {state.snapshot = null; await load();}
    else location.hash = searchButton.dataset.searchRoute;
  }
  const ackButton = event.target.closest('[data-ack]');
  if (ackButton) {
    ackButton.disabled = true;
    try {
      await api('/api/ack',{method:'POST',body:JSON.stringify({id:ackButton.dataset.ack,customer:state.customer,environment:state.environment,note:modal.querySelector('#ack-note')?.value || ''})});
      modal.close(); toast('운영자 확인 기록 완료'); await load();
    } catch(error) { toast(`확인 기록 실패 · ${error.message}`); ackButton.disabled = false; }
  }
  if (action === 'theme') { setTheme(preferences.theme === 'dark' ? 'light' : 'dark'); if (state.boot) render(); else loginScreen(); }
  if (action === 'refresh') await load();
  if (action === 'search') openSearch();
  if (action === 'close-modal') modal.close();
  if (action === 'logout') {
    try { await api('/api/logout',{method:'POST',body:'{}'}); } catch(error) { toast(`로그아웃 실패 · ${error.message}`); return; }
    controller?.abort(); requestSequence++; clearInterval(refreshTimer); modal.close();
    state.boot = null; state.snapshot = null; state.reports = []; state.events = []; state.admin = null;
    loginScreen();
  }
  if (action === 'account') openDialog('관제 계정',keyValues([['계정',esc(state.boot.user.login)],['이름',esc(state.boot.user.name || '\u2014')],['권한',esc(state.boot.user.role || 'Viewer')]]),'<a class="button" href="#settings" data-action="close-modal">설정</a><button class="button" data-action="logout">로그아웃</button>');
  if (action === 'reset-preferences') { setTheme('dark'); savePreferences({refresh:15,range:3600}); state.refresh=15;state.range=3600;schedule();render();toast('기본 화면 설정 복원'); }
  if (action === 'runbook') openDialog('DB 장애 · 수동 복구 절차',`<p>사람 개입 필요 · 비동기 복제의 유실 가능성 확인</p>${runbook()}<div class="settings-note" style="margin-top:20px">승격 전 기존 Primary의 쓰기 차단과 격리 확인. WAL 위치·마지막 커밋·복구 가능한 범위 확인 후 승격. 애플리케이션 연결 전환 후 요청·원장 검증, 기존 Primary는 새 Primary 기준으로 재구성. 자동 DB 페일오버 미사용.</div>`);
});

window.addEventListener('hashchange',()=>{
  const route = location.hash.slice(1);
  if (!routeMap[route] || !state.boot) return;
  state.route = route; state.tableSearch = ''; state.error = ''; state.snapshot = null;
  state.reports = []; state.events = []; state.admin = null; state.auxiliaryError = '';
  load();
});
document.addEventListener('keydown',(event)=>{
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && state.boot) { event.preventDefault(); openSearch(); }
});

function loginScreen(message = '') {
  app.innerHTML = `<button class="icon-button login-theme" data-action="theme" aria-label="색상 모드 전환">${icon(preferences.theme === 'dark' ? 'sun' : 'moon')}</button><main class="login-screen"><section class="login-copy"><div class="login-brand">${icon('brand')}MSP 관제센터</div><h1>고객 서비스의 상태,<br>한 화면의 운영 판단.</h1><p>Spring · PostgreSQL · AWS 관측<br>장애 확인부터 운영 보고까지.</p><div class="login-services"><span>${icon('activity')}Prometheus</span><span>${icon('grid')}Grafana 인증</span><span>${icon('report')}Teams 보고</span></div></section><form id="login-form" class="login-card"><h2>관제 계정 로그인</h2><p>Grafana에 등록된 계정으로 접속</p>${message ? `<div class="login-error" role="alert">${esc(message)}</div>` : ''}<label class="form-field">아이디<input name="username" autocomplete="username" required autofocus></label><label class="form-field">비밀번호<input type="password" name="password" autocomplete="current-password" required></label><button type="submit" class="button primary">로그인 ${icon('arrow')}</button><div class="login-footer">인증된 운영자 전용 · 세션 기반 접근<br>고객 서비스 지표와 운영 보고의 통합 조회</div></form></main>`;
  const form = document.querySelector('#login-form');
  form.addEventListener('submit',async(event)=>{
    event.preventDefault();
    const data = new FormData(form);
    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true; submit.textContent = '인증 중';
    try {
      await api('/api/login',{method:'POST',body:JSON.stringify({username:data.get('username'),password:data.get('password')})});
      form.reset(); await bootstrap();
    } catch(error) { loginScreen(error.status === 401 ? '아이디 또는 비밀번호 확인 필요' : `인증 연결 실패 · ${error.message}`); }
  });
}

async function bootstrap() {
  try {
    state.boot = await api('/api/bootstrap');
    const firstScope = state.boot.scopes?.[0];
    state.customer = firstScope?.customer || '';
    state.environment = firstScope?.environment || '';
    state.snapshot = null; state.error = ''; state.service = ''; state.instance = '';
    state.reports = []; state.events = []; state.admin = null; state.auxiliaryError = '';
    schedule(); await load();
  } catch(error) { state.boot = null; loginScreen(error.status === 401 ? '' : `관제 서비스 연결 실패 · ${error.message}`); }
}
bootstrap();
