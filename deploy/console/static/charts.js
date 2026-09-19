import {escapeHtml as esc, number, dateTime, segments} from './domain.js';
import {icon} from './icons.js';

const registry = new Map();
let sequence = 0;
const color = (index) => `var(--chart-${index % 6 + 1})`;

export function clearCharts() { registry.clear(); }

function label(series, fallback) {
  const labels = series.labels || {};
  return labels.legend || labels.code || labels.source || labels.node || labels.instance || labels.role || labels.name || labels.uri || fallback;
}

export function chart(metric, {unit = '', names = [], min = 0, max, threshold, start, end, spark = false} = {}) {
  const id = `chart-${++sequence}`;
  const series = metric?.series || [];
  const all = series.flatMap((item) => item.points || []).filter((point) => Number.isFinite(point[0]) && Number.isFinite(point[1]));
  const now = Date.now() / 1000;
  const begin = start || (all.length ? Math.min(...all.map((p) => p[0])) : now - 3600);
  const finish = end || (all.length ? Math.max(...all.map((p) => p[0])) : now);
  const xRange = Math.max(1, finish - begin);
  const top = max ?? Math.max(1, threshold || 0, ...all.map((point) => point[1])) * 1.12;
  const bottom = Math.min(min, ...all.map((point) => point[1]));
  const yRange = Math.max(.00001, top - bottom);
  const width = 430;
  const height = 130;
  const left = spark ? 0 : 58;
  const right = spark ? width : width - 7;
  const yTop = spark ? 1 : 6;
  const yBottom = spark ? height - 1 : height - 21;
  const x = (time) => left + (time - begin) / xRange * (right - left);
  const y = (value) => yBottom - (value - bottom) / yRange * (yBottom - yTop);
  let svg = '';
  if (!spark) {
    for (let index = 0; index <= 4; index++) {
      const value = bottom + yRange * index / 4;
      const pos = y(value);
      svg += `<line class="chart-grid" x1="${left}" y1="${pos}" x2="${right}" y2="${pos}"/><text class="chart-axis" x="${left - 6}" y="${pos + 3}" text-anchor="end">${esc(number(value, unit, 1))}</text>`;
    }
    for (let index = 0; index <= 4; index++) {
      const time = begin + xRange * index / 4;
      const pos = x(time);
      const text = dateTime(time).slice(0, 5);
      svg += `<line class="chart-grid" x1="${pos}" y1="${yTop}" x2="${pos}" y2="${yBottom}"/><text class="chart-axis" x="${pos}" y="${height - 4}" text-anchor="${index === 0 ? 'start' : index === 4 ? 'end' : 'middle'}">${esc(text)}</text>`;
    }
    if (Number.isFinite(threshold)) svg += `<line class="chart-threshold" x1="${left}" y1="${y(threshold)}" x2="${right}" y2="${y(threshold)}"/>`;
  }
  series.slice(0, 12).forEach((item, index) => {
    const points = item.points || [];
    const intervals = points.slice(1).map((point, i) => point[0] - points[i][0]).filter((gap) => gap > 0).sort((a, b) => a - b);
    const cadence = intervals[Math.floor(intervals.length / 2)] || Infinity;
    for (const segment of segments(points, cadence * 2.5)) {
      const d = segment.map((point, i) => `${i ? 'L' : 'M'}${x(point[0]).toFixed(2)},${y(point[1]).toFixed(2)}`).join(' ');
      if (segment.length === 1) svg += `<circle class="chart-point" cx="${x(segment[0][0])}" cy="${y(segment[0][1])}" r="2" fill="${color(index)}"/>`;
      else {
        if (index === 0 && series.length <= 2) svg += `<path class="chart-fill" fill="${color(index)}" d="${d} L${x(segment.at(-1)[0])},${yBottom} L${x(segment[0][0])},${yBottom} Z"/>`;
        svg += `<path class="chart-series" stroke="${color(index)}" d="${d}"/>`;
      }
    }
  });
  if (spark) return `<svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" aria-hidden="true">${svg}</svg>`;
  registry.set(id, {series, unit, names, begin, finish, left, right, width});
  const missing = metric?.state === 'error' ? '수집 오류' : '자료 없음';
  return `<div class="chart" data-chart="${id}" tabindex="0" aria-label="${esc(unit || '지표')} 시계열"><svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="실측 시계열">${svg}<line class="chart-cursor" y1="${yTop}" y2="${yBottom}"/></svg>${!all.length ? `<div class="chart-missing">${icon(metric?.state === 'error' ? 'warning' : 'unknown')}${missing}</div>` : ''}<div class="chart-tooltip"></div></div><div class="legend">${series.slice(0, 6).map((item, index) => `<span class="legend-item"><i class="legend-dot" style="background:${color(index)}"></i><span class="legend-name" title="${esc(label(item, names[index] || '전체'))}">${esc(names[index] || label(item, '전체'))}</span><strong class="legend-value">${esc(number(item.value, unit))}</strong></span>`).join('')}${series.length > 6 ? `<span class="muted">외 ${series.length - 6}개</span>` : ''}${threshold !== undefined ? `<span class="legend-item"><i class="legend-dot" style="background:var(--warning)"></i>임계 ${esc(number(threshold, unit))}</span>` : ''}</div>`;
}

export function wireCharts(root = document) {
  root.querySelectorAll('[data-chart]').forEach((element) => {
    const data = registry.get(element.dataset.chart);
    if (!data) return;
    const tooltip = element.querySelector('.chart-tooltip');
    const cursor = element.querySelector('.chart-cursor');
    const show = (fraction, clientX) => {
      const time = data.begin + Math.max(0, Math.min(1, fraction)) * (data.finish - data.begin);
      const rows = data.series.slice(0, 8).map((item, index) => {
        const points = item.points || [];
        const nearest = points.reduce((previous, point) => !previous || Math.abs(point[0] - time) < Math.abs(previous[0] - time) ? point : previous, null);
        const value = nearest?.[1];
        return `<div><i class="legend-dot" style="background:${color(index)}"></i><span>${esc(data.names[index] || label(item, '전체'))}</span><span>${esc(number(value, data.unit))}</span></div>`;
      }).join('');
      tooltip.innerHTML = `<strong>${dateTime(time, true)} KST</strong>${rows || '자료 없음'}`;
      tooltip.style.display = 'block';
      const rect = element.getBoundingClientRect();
      tooltip.style.left = `${Math.max(0, Math.min(rect.width - tooltip.offsetWidth, clientX ?? rect.width / 2))}px`;
      tooltip.style.top = '0px';
      cursor.style.display = 'block';
      cursor.setAttribute('x1', data.left + fraction * (data.right - data.left));
      cursor.setAttribute('x2', data.left + fraction * (data.right - data.left));
    };
    element.addEventListener('pointermove', (event) => {
      const rect = element.getBoundingClientRect();
      const pos = (event.clientX - rect.left) / rect.width * data.width;
      show(Math.max(0, Math.min(1, (pos - data.left) / (data.right - data.left))), event.clientX - rect.left + 14);
    });
    const hide = () => { tooltip.style.display = 'none'; cursor.style.display = 'none'; };
    element.addEventListener('pointerleave', hide);
    element.addEventListener('blur', hide);
    let keyboardPosition = .5;
    element.addEventListener('focus', () => show(keyboardPosition));
    element.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        keyboardPosition = Math.max(0, Math.min(1, keyboardPosition + (event.key === 'ArrowRight' ? .02 : -.02)));
        show(keyboardPosition);
      }
      if (event.key === 'Escape') hide();
    });
  });
}
