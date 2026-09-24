/**
 * 관리 콘솔의 시각·기간 글자. 화면 조각과 AI 상담의 답이 같은 말로 시각을 적게 한곳에 둔다.
 * 시각은 이 브라우저의 지역 시간으로 적는다. 보고 서비스처럼 KST를 밝혀야 하는 자리는 kst()를 쓴다.
 */

export function mmss(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function hmmss(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  return `${h}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function hm(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function hms(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
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
  if (m < 60) return `${m}분 ${String(s % 60).padStart(2, "0")}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

const KST_PARTS = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/**
 * 보고 서비스와 같은 꼴의 KST 시각('2026-09-25 09:00 KST'). 보고와 Teams 카드는 받는 사람의 지역과 무관하게
 * KST로 적으므로 브라우저 시간대를 따르지 않는다
 */
export function kst(iso: string, withDate = true): string {
  const p = Object.fromEntries(KST_PARTS.formatToParts(new Date(iso)).map((x) => [x.type, x.value]));
  const time = `${p.hour}:${p.minute}`;
  return withDate ? `${p.year}-${p.month}-${p.day} ${time} KST` : `${time} KST`;
}
