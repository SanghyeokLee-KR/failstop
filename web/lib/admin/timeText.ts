/**
 * 관리 콘솔의 시각·기간 글자. 화면 조각과 AI 상담의 답이 같은 말로 시각을 적게 한곳에 둔다.
 * 시각은 브라우저 시간대와 무관하게 KST로 적는다(디자인 시스템 §12-12). 한국은 일광 절약 시간이 없어
 * UTC에 9시간을 더한 값의 UTC 필드가 곧 KST다.
 */

const KST_OFFSET_MS = 9 * 3_600_000;
const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];

const p2 = (n: number) => String(n).padStart(2, "0");

/** KST 벽시계의 각 자리. 시계와 날짜 글자가 모두 이 값을 쓴다 */
export function kstParts(ms: number): { month: number; day: number; weekday: string; h: number; m: number; s: number } {
  const d = new Date(ms + KST_OFFSET_MS);
  return { month: d.getUTCMonth() + 1, day: d.getUTCDate(), weekday: WEEKDAY[d.getUTCDay()], h: d.getUTCHours(), m: d.getUTCMinutes(), s: d.getUTCSeconds() };
}

/** KST 날짜 열쇠('2026-09-25'). 콘솔의 '오늘'은 브라우저 시간대가 아니라 KST 자정으로 가른다(§12-12) */
export function kstDay(ms: number): string {
  return new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 10);
}

/** 이 시각 이후 가장 가까운 KST 시:분의 시각(ms). 예약 작업(백업, 일일 보고)의 다음 시각이다 */
export function nextKst(now: number, hour: number, minute: number): number {
  const d = new Date(now + KST_OFFSET_MS);
  d.setUTCHours(hour, minute, 0, 0);
  let t = d.getTime() - KST_OFFSET_MS;
  if (t <= now) t += 86_400_000;
  return t;
}

export function mmss(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${p2(Math.floor(s / 60))}:${p2(s % 60)}`;
}

/**
 * 남은 시간. 1시간 이상은 시와 분(분은 내림), 1시간 아래는 분, 1분 아래는 초다. 사이드바 바닥과 모드 줄이
 * 같은 꼴로 적는다
 */
export function remainWord(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

export function hm(iso: string): string {
  const k = kstParts(Date.parse(iso));
  return `${p2(k.h)}:${p2(k.m)}`;
}

export function hms(iso: string): string {
  const k = kstParts(Date.parse(iso));
  return `${p2(k.h)}:${p2(k.m)}:${p2(k.s)}`;
}

/**
 * 기록의 시각 한 형식(디자인 시스템 §12-12). KST 오늘이면 hh:mm:ss, 다른 날이면 'M.D hh:mm'이다. 여러 날의 기록이 섞인 목록(검색 결과,
 * 조치 기록, 판단 기록)이 이 형식을 쓴다. 시각만 적으면 어제 기록이 오늘 기록처럼 읽힌다
 */
export function stamp(iso: string, now: number): string {
  return kstDay(Date.parse(iso)) === kstDay(now) ? hms(iso) : mdhm(iso);
}

/** 날짜가 필요한 시각('9.25 03:10') */
export function mdhm(iso: string): string {
  const k = kstParts(Date.parse(iso));
  return `${k.month}.${k.day} ${p2(k.h)}:${p2(k.m)}`;
}

export function ago(iso: string, now: number): string {
  const s = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (s < 60) return `${s}초 전`;
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  return `${Math.floor(s / 3600)}시간 전`;
}

export function durationWord(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 ${p2(s % 60)}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

/**
 * 긴 지난 기간(가동 시간). 하루 이상은 일과 시간, 한 시간 이상은 시간과 분, 그 아래는 분이다. 자산 표와 노드 상세가
 * 같은 꼴로 적는다
 */
export function spanWord(ms: number): string {
  const m = Math.max(0, Math.floor(ms / 60_000));
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 ${m % 60}분`;
  return `${Math.floor(h / 24)}일 ${h % 24}시간`;
}

/** KST 날짜와 요일('9.25 금'). 달력과 교대표가 쓴다 */
export function mdw(iso: string): string {
  const k = kstParts(Date.parse(iso));
  return `${k.month}.${k.day} ${k.weekday}`;
}

/** 이 시각을 품은 주의 시작. 월요일 KST hour시다. 당번 교대가 이 경계에서 바뀐다 */
export function weekStartKst(ms: number, hour = 9): number {
  const d = new Date(ms + KST_OFFSET_MS);
  const back = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - back);
  d.setUTCHours(hour, 0, 0, 0);
  let t = d.getTime() - KST_OFFSET_MS;
  if (t > ms) t -= 7 * 86_400_000;
  return t;
}

/** 초 단위 지연(복제 지연). 소수 첫째 자리로 고정해 '1초'와 '0.3초'가 한 자리에서 자릿수를 바꾸지 않게 한다(§12-12) */
export function lagWord(sec: number): string {
  return `${sec.toFixed(1)}초`;
}

/** 짧은 지연. 1초 아래는 ms 정수, 그 위는 초를 소수 첫째 자리로 적는다 */
export function secWord(ms: number): string {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}초` : `${Math.round(ms)}ms`;
}

/**
 * 보고 서비스와 같은 꼴의 KST 시각('2026-09-25 09:00 KST'). 보고와 Teams 카드는 밖으로 나가는 문서라
 * 'KST'를 붙인다. 콘솔 화면의 다른 시각은 운영자 바 시계가 기준을 한 번 밝히므로 붙이지 않는다
 */
export function kst(iso: string, withDate = true): string {
  const d = new Date(Date.parse(iso) + KST_OFFSET_MS);
  const time = `${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}`;
  return withDate ? `${d.getUTCFullYear()}-${p2(d.getUTCMonth() + 1)}-${p2(d.getUTCDate())} ${time} KST` : `${time} KST`;
}
