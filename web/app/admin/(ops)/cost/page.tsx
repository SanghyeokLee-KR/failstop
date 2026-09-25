/**
 * 비용(/admin/cost, 관리자 설계 4-10-5). 예산과 크레딧 안에서 언제까지 운영할 수 있는지에 답한다. 값은 계획
 * 계산이고 실제 청구액은 AWS Cost Explorer를 읽지 않아 원천 연결 전이다
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function CostPage() {
  return <ScreenPending title="비용" section="4-10-5" />;
}
