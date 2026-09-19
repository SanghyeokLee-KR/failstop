export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[char]));
}

export function safeHref(value) {
  if (typeof value !== 'string' || !value.trim()) return '';
  try {
    const url = new URL(value, 'http://console.invalid');
    return ['http:', 'https:'].includes(url.protocol) ? value : '';
  } catch { return ''; }
}

export function metricValue(metric, operation = 'sum') {
  if (!metric || metric.state !== 'ok') return null;
  const values = (metric.series || []).map((series) => series.value).filter(Number.isFinite);
  if (!values.length) return null;
  if (operation === 'max') return Math.max(...values);
  if (operation === 'min') return Math.min(...values);
  if (operation === 'average') return values.reduce((sum, value) => sum + value, 0) / values.length;
  return values.reduce((sum, value) => sum + value, 0);
}

export function statusFromMetric(metric) {
  if (!metric || metric.state !== 'ok' || !metric.series?.length) return 'unknown';
  const values = metric.series.map((series) => series.value);
  if (values.some((value) => Number.isFinite(value) && value <= 0)) return 'danger';
  return values.every(Number.isFinite) ? 'good' : 'unknown';
}

export function selectDbSeries(metrics, role, id) {
  const expected = role === 'primary' ? 0 : 1;
  const instances = new Set((metrics.db_recovery?.series || [])
    .filter((series) => series.value === expected)
    .map((series) => series.labels?.instance));
  return (metrics[id]?.series || []).filter((series) => instances.has(series.labels?.instance));
}

export function statusForTargets(metric, targets, job) {
  const status = statusFromMetric(metric);
  if (status !== 'good') return status;
  const expected = (targets || []).filter((target) => target.labels?.job === job);
  const received = new Set(metric.series.filter((series) => Number.isFinite(series.value)).map((series) => series.labels?.instance));
  if (!expected.length || expected.some((target) => target.health !== 'up' || !received.has(target.labels?.instance))) return 'unknown';
  return 'good';
}

export function number(value, unit = '', precision) {
  if (!Number.isFinite(value)) return '\u2014';
  if (unit === 'bytes' || unit === 'B/s') {
    const size = Math.abs(value);
    const divisor = size >= 1024 ** 3 ? 1024 ** 3 : size >= 1024 ** 2 ? 1024 ** 2 : size >= 1024 ? 1024 : 1;
    const suffix = divisor === 1024 ** 3 ? 'GiB' : divisor === 1024 ** 2 ? 'MiB' : divisor === 1024 ? 'KiB' : 'B';
    return `${new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 1}).format(value / divisor)} ${suffix}${unit === 'B/s' ? '/s' : ''}`;
  }
  const digits = precision ?? (unit === '%' || unit === 'ms' ? 1 : Math.abs(value) >= 100 ? 0 : 2);
  return `${new Intl.NumberFormat('ko-KR', {maximumFractionDigits: digits}).format(value)}${unit === '%' ? '%' : unit ? ` ${unit}` : ''}`;
}

export function segments(points, maxGap = Infinity) {
  const result = [];
  let segment = [];
  for (const point of points || []) {
    if (!Number.isFinite(point?.[0]) || !Number.isFinite(point?.[1])) {
      if (segment.length) result.push(segment);
      segment = [];
      continue;
    }
    if (segment.length && point[0] - segment.at(-1)[0] > maxGap) {
      result.push(segment);
      segment = [];
    }
    segment.push(point);
  }
  if (segment.length) result.push(segment);
  return result;
}

export function dateTime(value, full = false) {
  if (!value) return '\u2014';
  const date = new Date(typeof value === 'number' && value < 1e12 ? value * 1000 : value);
  if (!Number.isFinite(date.valueOf())) return '\u2014';
  return new Intl.DateTimeFormat('ko-KR', {timeZone: 'Asia/Seoul', ...(full ? {month:'2-digit', day:'2-digit'} : {}), hour:'2-digit', minute:'2-digit', second:'2-digit', hour12:false}).format(date);
}
