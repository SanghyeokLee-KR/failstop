import test from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml, safeHref, metricValue, statusFromMetric, statusForTargets, selectDbSeries, number, segments, dateTime} from './static/domain.js';
import {chart, clearCharts} from './static/charts.js';

test('외부 라벨의 HTML 실행 차단', () => {
  assert.equal(escapeHtml('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
  assert.equal(escapeHtml("'&"), '&#39;&amp;');
});

test('링크는 HTTP와 같은 출처 경로만 허용', () => {
  for (const href of ['javascript:alert(1)', 'data:text/html,<script>', 'file:///etc/passwd', '  javascript:alert(1)']) assert.equal(safeHref(href),'');
  assert.equal(safeHref('/api/report-file?path=report.pdf'),'/api/report-file?path=report.pdf');
  assert.equal(safeHref('https://grafana.example/d/test'),'https://grafana.example/d/test');
});

test('미수집·수집 실패를 0으로 집계하지 않음', () => {
  assert.equal(metricValue({state:'missing',series:[]}),null);
  assert.equal(metricValue({state:'error',series:[{value:42}]}),null);
  assert.equal(metricValue({state:'ok',series:[{value:null}]}),null);
  assert.equal(metricValue({state:'ok',series:[{value:0}]}),0);
  assert.equal(metricValue({state:'ok',series:[{value:20},{value:null}]},'average'),20);
});

test('부분 상태 수집과 실패 상태 구분', () => {
  assert.equal(statusFromMetric({state:'ok',series:[{value:1},{value:null}]}),'unknown');
  assert.equal(statusFromMetric({state:'ok',series:[{value:1},{value:0}]}),'danger');
  assert.equal(statusFromMetric({state:'ok',series:[{value:1}]}),'good');
  assert.equal(statusFromMetric({state:'error',series:[{value:1}]}),'unknown');
});

test('DB 승격 후 배치 라벨 대신 수집된 실제 복구 모드로 역할 결정', () => {
  const metrics = {
    db_recovery:{series:[{labels:{instance:'old-standby',role:'standby'},value:0},{labels:{instance:'old-primary',role:'data'},value:1}]},
    db_up:{series:[{labels:{instance:'old-standby'},value:1},{labels:{instance:'old-primary'},value:1}]},
  };
  assert.equal(selectDbSeries(metrics,'primary','db_up')[0].labels.instance,'old-standby');
  assert.equal(selectDbSeries(metrics,'standby','db_up')[0].labels.instance,'old-primary');
  metrics.db_recovery.series[0].value = null;
  assert.deepEqual(selectDbSeries(metrics,'primary','db_up'),[]);
});

test('한 대상의 상태 지표 누락 시 나머지 정상값으로 전체 정상 판정 금지', () => {
  const metric = {state:'ok',series:[{labels:{instance:'primary'},value:1}]};
  const targets = [{labels:{job:'postgres',instance:'primary'},health:'up'},{labels:{job:'postgres',instance:'standby'},health:'down'}];
  assert.equal(statusForTargets(metric,targets,'postgres'),'unknown');
  assert.equal(statusForTargets(metric,targets.slice(0,1),'postgres'),'good');
  metric.series[0].value = 0;
  assert.equal(statusForTargets(metric,targets,'postgres'),'danger');
});

test('시계열 누락 및 수집 공백을 선으로 연결하지 않음', () => {
  assert.deepEqual(segments([[1,1],[2,null],[3,2],[4,2],[20,3]],5),[[[1,1]],[[3,2],[4,2]],[[20,3]]]);
  assert.deepEqual(segments([[1,NaN],[2,Infinity]]),[]);
});

test('차트의 수집 오류와 미수집 구분 및 라벨 이스케이프', () => {
  clearCharts();
  assert.match(chart({state:'error',series:[]},{start:1,end:100}),/수집 오류/);
  const markup = chart({state:'ok',series:[{labels:{instance:'<img onerror=1>'},points:[[1,0],[2,null],[3,1]],value:null}]},{start:1,end:4});
  assert.ok(!markup.includes('<img onerror'));
  assert.ok(markup.includes('&lt;img onerror=1&gt;'));
  assert.ok(!markup.includes('NaN'));
  assert.ok(!markup.includes('Infinity'));
});

test('숫자와 KST 시간의 명시적 표시', () => {
  assert.equal(number(null),'\u2014');
  assert.equal(number(0,'%'),'0%');
  assert.equal(number(1048576,'bytes'),'1 MiB');
  assert.match(dateTime('2026-09-18T05:54:38Z'),/14:54:38/);
});
