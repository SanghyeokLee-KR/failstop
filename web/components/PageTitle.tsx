"use client";

/**
 * 클라이언트 페이지용 문서 제목. metadata export가 불가능한 "use client" 페이지가
 * 탭 제목을 잃지 않게 한다(멀티 탭 식별·브라우저 이력 판독).
 */

import { useEffect } from "react";

/** 게이트·로딩 등 이른 반환 분기와 무관하게 제목이 걸리도록 페이지 최상단에서 호출한다 */
export function usePageTitle(title: string): void {
  useEffect(() => {
    const set = () => {
      document.title = `${title} · 페일스톱`;
    };
    set();
    // 하이드레이션 직후 App Router의 metadata 동기화가 제목을 기본값으로 한 번 되돌린다.
    // 지연 재설정으로 마지막 쓰기를 이 훅이 가져간다
    const t = setTimeout(set, 400);
    return () => clearTimeout(t);
  }, [title]);
}

export default function PageTitle({ title }: { title: string }) {
  usePageTitle(title);
  return null;
}
