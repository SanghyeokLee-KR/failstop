/**
 * 이관 경로 전용 대기 프레임. return null은 replace가 붙기 전 순백 프레임을 깜빡인다.
 * 목적지 화면 골격을 흉내내지 않고 무지 스켈레톤 한 장만 깐다(수십 ms 노출 전제).
 */

import Skeleton from "@/components/Skeleton";

export default function RedirectShell() {
  return (
    <main aria-busy="true" style={{ padding: "var(--space-6) var(--layout-gutter)" }}>
      <Skeleton variant="card" width="100%" height={320} />
    </main>
  );
}
