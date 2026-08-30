/**
 * 공지 전달 규칙(API 설계 §4-F). 심각도는 색이 아니라 채널을 정한다.
 * CRITICAL은 닫을 수 없는 배너, WARNING은 닫을 수 있는 배너, INFO는 목록에만 둔다.
 * 판정을 순수 함수로 두는 이유는 DOM 없이 검증하기 위해서다. 이 규칙의 오류는 조용히 일어난다.
 */

import type { NoticeSeverity, PublicNotice } from "./types";

/** 배너로 올라가는 심각도. INFO는 목록에만 둔다 */
const BANNER_SEVERITY: NoticeSeverity[] = ["CRITICAL", "WARNING"];

/** 닫을 수 없는 심각도. 모르면 손해를 보는 내용이라 사용자 선택으로 지울 수 없다 */
export function isDismissible(severity: NoticeSeverity): boolean {
  return severity !== "CRITICAL";
}

/**
 * 배너에 올릴 공지 하나를 고른다. 배너는 화면당 하나다. 여러 개를 쌓으면 결국 아무것도 안 읽힌다.
 * CRITICAL이 있으면 그것, 없으면 WARNING 중 최신이고, 같은 등급이 여럿이면 최신이 이긴다.
 *
 * @param dismissedIds 사용자가 닫은 공지 id. CRITICAL은 이 목록을 무시한다
 */
export function bannerNotice(
  notices: PublicNotice[],
  dismissedIds: readonly string[] = [],
): PublicNotice | null {
  const dismissed = new Set(dismissedIds);
  const candidates = notices
    .filter((n) => BANNER_SEVERITY.includes(n.severity))
    .filter((n) => n.severity === "CRITICAL" || !dismissed.has(n.id))
    .sort((a, b) => {
      // 등급이 먼저다. 최신 WARNING이 오래된 CRITICAL을 덮으면 안 된다
      if (a.severity !== b.severity) return a.severity === "CRITICAL" ? -1 : 1;
      return (b.published_at ?? 0) - (a.published_at ?? 0);
    });
  return candidates[0] ?? null;
}

/**
 * 진입점 표식 판정. 마지막으로 본 시각 이후 발행된 공지가 있으면 띄운다.
 * 읽음 상태는 브라우저 로컬에만 둔다. 기기를 바꾸면 표식이 다시 뜨는 것이 그 대가다.
 */
export function hasUnread(notices: PublicNotice[], lastSeenAt: number | null): boolean {
  if (notices.length === 0) return false;
  if (lastSeenAt === null) return true;
  return notices.some((n) => (n.published_at ?? 0) > lastSeenAt);
}

/** 목록 정렬. 최신순. 심각도는 정렬 축이 아니다(시간 순서가 읽는 순서다) */
export function sortNotices(notices: PublicNotice[]): PublicNotice[] {
  return [...notices].sort((a, b) => (b.published_at ?? 0) - (a.published_at ?? 0));
}
