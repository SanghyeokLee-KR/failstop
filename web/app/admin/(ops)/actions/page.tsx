/**
 * 조치 기록(/admin/actions, 관리자 설계 4-10-4). 누가(사람·AI·시스템) 무엇을 언제 바꿨고 결과가 어땠는지에 답한다.
 * 원천은 감사 로그와 조치 요청 목록이라 http에서도 실측을 그린다
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function ActionsPage() {
  return <ScreenPending title="조치 기록" section="4-10-4" />;
}
