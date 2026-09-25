import AdminShell from "@/components/admin/AdminShell";
import AiStop from "@/components/admin/control/AiStop";
import ChatHost, { ChatTrigger } from "@/components/admin/chat/ChatHost";
import { AiModeFoot } from "@/components/admin/control/NavStatus";
import DetailHost from "@/components/admin/detail/DetailHost";
import OpsSearch from "@/components/admin/control/OpsSearch";
import OpsSignals from "@/components/admin/control/OpsSignals";
import { OPS_FOOT, OPS_NAV } from "@/lib/admin/nav";

/**
 * 운영 관제 셸. 관제, 인프라, 조치, 시연이 여기 있다. 운영자 바에 관제 검색, AI 즉시 정지와 전체 상태·경보 종을,
 * 사이드바 바닥에 AI 운영 모드와 설정을, 명판 줄에 AI 상담 단추(Ctrl+J)를 둔다. 상세 층(노드·사건·AI 판단)은
 * 셸이 한곳에서 그려 어느 화면에서 열어도 같다
 */
export default function OpsLayout({ children }: { children: React.ReactNode }) {
  return (
    <AdminShell
      title="운영 관제"
      groups={OPS_NAV}
      footLinks={OPS_FOOT}
      crossLink={{ href: "/admin/back", label: "백오피스로" }}
      sidebarFoot={<AiModeFoot />}
      brandAction={<ChatTrigger />}
      barSearch={<OpsSearch />}
      barAi={<AiStop />}
      barSignals={<OpsSignals />}
      overlay={
        <>
          <ChatHost />
          <DetailHost />
        </>
      }
    >
      {children}
    </AdminShell>
  );
}
