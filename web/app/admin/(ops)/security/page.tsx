/**
 * 네트워크·보안(/admin/security, 관리자 설계 4-10-3). 어디로 들어올 수 있고, 무엇이 곧 만료되며, 누가 관리 권한을
 * 썼는지에 답한다. 허용 경로 표는 설계 의도이고 AWS에서 읽은 값이 아니다
 */

import ScreenPending from "@/components/admin/control/ScreenPending";

export default function SecurityPage() {
  return <ScreenPending title="네트워크·보안" section="4-10-3" />;
}
