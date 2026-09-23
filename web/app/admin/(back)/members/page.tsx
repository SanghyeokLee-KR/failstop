"use client";

/**
 * 정지·복구는 가역이라 즉시 실행하고, 폐쇄는 비가역이라 1인 승인을 거친다(§6-E, ADR 0019).
 * 상세를 옆에 세우는 이유는 폐쇄 판단에 미체결 주문 수가 필요해서다.
 */

import { useCallback, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import type {
  AdminMember,
  AdminMemberDetail,
  MemberStatus,
} from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import SegmentedTab from "@/components/SegmentedTab";
import DataTable, { type DataColumn } from "@/components/DataTable";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import { PageHeader, PermissionNote } from "@/components/admin/ui";
import { useCursorPages } from "@/components/admin/useCursorPages";
import { QUEUE_PAGE_SIZE } from "@/lib/admin/paging";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { useCan } from "@/lib/admin/store";
import { toast } from "@/components/Toast";
import { formatCount, formatHistoryTime } from "@/lib/format";
import type { DestructiveAction } from "@/lib/admin/types";
import styles from "@/components/admin/admin.module.css";

const TABS = [
  { value: "ALL" as const, label: "전체" },
  { value: "ACTIVE" as const, label: "정상" },
  { value: "SUSPENDED" as const, label: "정지" },
  { value: "CLOSED" as const, label: "폐쇄" },
];

const STATUS_LABEL: Record<MemberStatus, string> = {
  ACTIVE: "정상",
  SUSPENDED: "정지",
  CLOSED: "폐쇄",
};

const CLOSE_ACTIONS: DestructiveAction[] = ["members.close"];

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

export default function AdminMembersPage() {
  const canMod = useCan("MEMBER_MOD");
  const [filter, setFilter] = useState<MemberStatus | "ALL">("ALL");
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<AdminMemberDetail | null>(null);
  const [busy, setBusy] = useState(false);

  const loader = useCallback(
    (cursor: string | null) =>
      getAdminApi().getMembers(filter === "ALL" ? undefined : filter, {
        cursor,
        limit: QUEUE_PAGE_SIZE,
      }),
    [filter],
  );
  const { page, rows, hasMore, move, res } = useCursorPages<AdminMember>(loader, {
    key: filter,
    isSessionError,
  });

  /* 상세는 선택할 때만 읽는다. 목록 폴링에 얹으면 안 보는 회원까지 매 주기 조회한다 */
  const loadDetail = useCallback(async (externalId: string) => {
    try {
      setDetail(await getAdminApi().getMember(externalId));
    } catch (e) {
      setDetail(null);
      toast(e instanceof Error ? e.message : "상세 조회 실패", "error");
    }
  }, []);

  const select = (externalId: string) => {
    setSelected(externalId);
    setDetail(null);
    void loadDetail(externalId);
  };

  const transition = async (externalId: string, to: "SUSPENDED" | "ACTIVE") => {
    if (busy) return;
    setBusy(true);
    try {
      const api = getAdminApi();
      const t = to === "SUSPENDED"
        ? await api.suspendMember(externalId)
        : await api.reinstateMember(externalId);
      // 무엇이 함께 정리됐는지 되돌려 준다. 세션 폐기는 화면에 보이지 않는 부수 효과다
      const extra = t.revokedSessions > 0 ? ` · 세션 ${t.revokedSessions}건 폐기` : "";
      toast(`${STATUS_LABEL[t.status]} 처리 완료${extra}`, "success");
      void res.reload();
      if (selected === externalId) void loadDetail(externalId);
    } catch (e) {
      toast(e instanceof Error ? e.message : "처리 실패", "error");
    } finally {
      setBusy(false);
    }
  };

  /* 열 최소 폭과 열 간격(8px)의 합은 1280 폭에서 표 안쪽 940px에 들어야 한다.
     넘치면 격자가 오른쪽 열부터 카드 밖으로 밀린다 */
  const cols: DataColumn<AdminMember>[] = [
    {
      key: "email",
      label: "이메일",
      width: "minmax(200px, 1.4fr)",
      render: (m) => (
        <button
          type="button"
          className={styles.linkCell}
          onClick={() => select(m.externalId)}
          aria-pressed={selected === m.externalId}
        >
          {m.email}
        </button>
      ),
    },
    { key: "displayName", label: "표시명", width: "minmax(110px, 0.9fr)", render: (m) => m.displayName ?? "-" },
    {
      key: "status",
      label: "상태",
      width: "88px",
      // 정상은 잉크로 적는다. 이탈(정지·폐쇄)만 알약으로 세운다
      render: (m) =>
        m.status === "ACTIVE" ? (
          <span className={styles.stateText}>정상</span>
        ) : (
          <span className={styles.neutralPill}>{STATUS_LABEL[m.status]}</span>
        ),
    },
    /* 활동 요약 3열(§6-E v2.35). 상세를 열어야 보이던 값을 목록으로 올린다.
       정지·폐쇄 대상을 고르는 곳이 목록이고, 폐쇄는 미체결을 전량 취소하므로
       그 수는 조치 전에 보여야 한다. 0은 잉크를 낮춰 남은 건이 있는 행이 먼저 눈에 들게 한다 */
    {
      key: "openOrders",
      label: "미체결",
      width: "88px",
      render: (m) =>
        m.openOrderCount > 0 ? (
          <span className="num">{formatCount(m.openOrderCount)}</span>
        ) : (
          <span className={styles.stateText}>0</span>
        ),
    },
    {
      key: "assetKinds",
      label: "보유 종류",
      width: "96px",
      render: (m) =>
        m.assetKinds > 0 ? (
          <span className="num">{formatCount(m.assetKinds)}</span>
        ) : (
          <span className={styles.stateText}>0</span>
        ),
    },
    {
      key: "lastOrderAt",
      label: "최근 주문",
      width: "116px",
      // 주문 이력이 없으면 시각이 아니라 없음이다. 0시로 채우면 1970년이 뜬다
      render: (m) =>
        m.lastOrderAt ? (
          <span className="num">{formatHistoryTime(new Date(m.lastOrderAt).toISOString())}</span>
        ) : (
          <span className={styles.stateText}>-</span>
        ),
    },
    {
      key: "createdAt",
      label: "가입",
      width: "116px",
      render: (m) => <span className="num">{formatHistoryTime(new Date(m.createdAt).toISOString())}</span>,
    },
    {
      key: "actions",
      label: "조치",
      // 버튼 하나(56px)만 담는다. 좌우 4px만 남긴다
      width: "64px",
      render: (m) => {
        if (m.status === "CLOSED") return <span className={styles.auditNil}>종결</span>;
        return (
          <div className={styles.rowActions}>
            {m.status === "ACTIVE" ? (
              <button
                type="button"
                className={styles.neutralBtn}
                disabled={!canMod || busy}
                onClick={() => void transition(m.externalId, "SUSPENDED")}
              >
                정지
              </button>
            ) : (
              <button
                type="button"
                className={styles.neutralBtn}
                disabled={!canMod || busy}
                onClick={() => void transition(m.externalId, "ACTIVE")}
              >
                복구
              </button>
            )}
          </div>
        );
      },
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        title="회원 운영"
        desc="정지·복구는 즉시 반영, 폐쇄는 비가역이라 1인 승인"
        actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
      />

      {!canMod && <PermissionNote>회원 운영 권한(MEMBER_MOD 이상) 없음. 조회만 가능</PermissionNote>}

      <div className={styles.transferTabs}>
        <SegmentedTab items={TABS} value={filter} onChange={setFilter} ariaLabel="회원 상태 필터" />
      </div>

      <PanelCard title="회원 목록">
        {res.error ? (
          <AdminErrorState reason={res.errorReason} onRetry={() => void res.reload()} />
        ) : res.data === null ? (
          <Skeleton variant="card" width="100%" height={240} />
        ) : rows.length === 0 ? (
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">해당 상태의 회원 없음</span>
          </div>
        ) : (
          <DataTable columns={cols} rows={rows} rowKey={(m) => m.externalId} rowHeight={44} ariaLabel="회원 목록" defaultAlign="center" />
        )}
        <Pager
          page={page}
          hasMore={hasMore}
          onMove={move}
          ariaLabel="회원 목록 쪽 이동"
          busy={busy}
        />
      </PanelCard>

      {selected && (
        <PanelCard
          title="선택 회원"
          actions={<span className={styles.panelMeta}>폐쇄는 1인 승인</span>}
        >
          {!detail ? (
            <Skeleton variant="card" width="100%" height={120} />
          ) : (
            <>
              <div className={styles.readoutRow}>
                <span className={styles.readoutItem}>
                  <span className={styles.readoutKey}>이메일</span>
                  <b className={styles.readoutValue}>{detail.email}</b>
                </span>
                <span className={styles.readoutItem}>
                  <span className={styles.readoutKey}>상태</span>
                  <b className={styles.readoutValue}>{STATUS_LABEL[detail.status]}</b>
                </span>
                {/* 폐쇄가 무엇을 함께 취소하는지 여기서 읽고 누른다 */}
                <span className={styles.readoutItem}>
                  <span className={styles.readoutKey}>미체결 주문</span>
                  <b className={`${styles.readoutValue} num`}>{formatCount(detail.openOrderCount)}</b>
                </span>
                <span className={styles.readoutItem}>
                  <span className={styles.readoutKey}>보유 자산</span>
                  <b className={`${styles.readoutValue} num`}>{formatCount(detail.assetKinds)}</b>
                </span>
                <span className={styles.readoutItem}>
                  <span className={styles.readoutKey}>최근 주문</span>
                  <b className={styles.readoutValue}>
                    {detail.lastOrderAt
                      ? formatHistoryTime(new Date(detail.lastOrderAt).toISOString())
                      : "없음"}
                  </b>
                </span>
              </div>

              {detail.status === "CLOSED" ? (
                <p className={styles.formHelp}>폐쇄된 계정. 되돌아오는 전이 없음</p>
              ) : (
                <div className={styles.controlRow}>
                  <p className={styles.formHelp}>
                    폐쇄는 미체결 주문 {formatCount(detail.openOrderCount)}건을 전량 취소하고 세션을 끊는다.
                    되돌릴 수 없다.
                  </p>
                  <RequestButton
                    action="members.close"
                    payload={{ action: "members.close", externalId: detail.externalId, email: detail.email }}
                    label="폐쇄 요청"
                    confirm={{
                      title: "회원 폐쇄 요청",
                      description: "승인·실행 뒤 되돌릴 수 없음. 미체결 주문 전량 취소",
                      target: detail.email,
                      blastRadius: `미체결 ${detail.openOrderCount}건 취소 · 세션 전량 폐기`,
                      tone: "danger",
                      confirmLabel: "폐쇄 요청",
                    }}
                    onRequested={() => void res.reload()}
                  />
                </div>
              )}
            </>
          )}
          <ApprovalList
            actions={CLOSE_ACTIONS}
            onChange={() => {
              void res.reload();
              if (selected) void loadDetail(selected);
            }}
            emptyLabel="대기 중인 폐쇄 승인 없음"
            emptyDesc="위에서 요청하면 여기에서 발급·실행"
          />
        </PanelCard>
      )}
    </div>
  );
}
