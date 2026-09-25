/**
 * 변경·작업(/admin/changes, 관리자 설계 4-10-4). 언제 무엇을 바꾸기로 했고 그동안 무엇이 허용되는지에 답한다.
 * AI의 경보 보류는 계획 작업 창 안의 warning 경보뿐이다(ADR 0035)
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function ChangesPage() {
  return <ScreenPending title="변경·작업" section="4-10-4" />;
}
