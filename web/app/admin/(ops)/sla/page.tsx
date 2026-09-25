/**
 * SLA 월간 보고(/admin/sla, 관리자 설계 4-10-5). 이번 달 약속한 서비스 수준을 지켰는지에 답한다. 가용성은 외부
 * 프로브 성공률이고 주문 API SLO(API 설계 §7-4)와 섞지 않는다
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function SlaPage() {
  return <ScreenPending title="SLA 월간 보고" section="4-10-5" />;
}
