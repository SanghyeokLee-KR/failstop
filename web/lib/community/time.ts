/**
 * lib/community/time.ts. 게시판 상대 시각 표기.
 * 거래 화면은 HMS(초 단위)만 쓰므로(format.ts) 게시판용 일/시/분 상대 표기를 별도로 둔다.
 */

const nfDate = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});
const nfDateTime = new Intl.DateTimeFormat("ko-KR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

/** "방금 전" / "N분 전" / "N시간 전" / "어제" / "N일 전" / 날짜(7일 초과) */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const t = new Date(iso).getTime();
  if (!Number.isFinite(t)) return "";
  const diff = Math.max(0, now - t);
  const min = Math.floor(diff / 60_000);
  if (min < 1) return "방금 전";
  if (min < 60) return `${min}분 전`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour}시간 전`;
  const day = Math.floor(hour / 24);
  if (day === 1) return "어제";
  if (day < 7) return `${day}일 전`;
  return nfDate.format(t);
}

/** 절대 시각(툴팁·상세 헤더용) */
export function formatAbsoluteTime(iso: string): string {
  const t = new Date(iso).getTime();
  if (!Number.isFinite(t)) return "";
  return nfDateTime.format(t);
}
