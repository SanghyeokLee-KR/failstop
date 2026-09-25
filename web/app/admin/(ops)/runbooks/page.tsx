/**
 * 런북(/admin/runbooks, 관리자 설계 4-10-4). 이 상황에서 사람은 무엇을 어떤 순서로 확인하고 되돌리는지에 답한다.
 * 운영자가 SSM 세션에서 실행하는 절차라 이 화면에는 실행 단추가 없다
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function RunbooksPage() {
  return <ScreenPending title="런북" section="4-10-4" />;
}
