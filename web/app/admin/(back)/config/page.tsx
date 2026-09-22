"use client";

/**
 * 마켓·공지(/admin/config). 마켓 halt/resume/수수료는 SUPER + 1인 승인(발급형, ADR 0019).
 * DELISTED는 종국 상태라 재개 대상이 아니다. 공지 발급은 SUPER 즉시(가역), 소프트 삭제·active 토글 제공.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { feeRateError } from "@/lib/admin/fees";
import { useCan } from "@/lib/admin/store";
import {
  NOTICE_SEVERITIES,
  NOTICE_SEVERITY_LABEL,
  type ConfigMarket,
  type DestructiveAction,
  type Notice,
  type NoticeSeverity,
} from "@/lib/admin/types";
import { formatKRW, formatTimeHMS } from "@/lib/format";
import PanelCard from "@/components/PanelCard";
import Pager from "@/components/Pager";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import RequestButton from "@/components/admin/RequestButton";
import ApprovalList from "@/components/admin/ApprovalList";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { HealthPill, PageHeader, PermissionNote } from "@/components/admin/ui";
import { toast } from "@/components/Toast";
import { mulDecimal } from "@/lib/decimal";
import styles from "@/components/admin/admin.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

const MARKET_ACTIONS: DestructiveAction[] = ["config.market.halt", "config.market.resume", "config.market.fees"];

/** severity -> 상태 색. info는 중립, warning 주의, critical 위험 */
const SEVERITY_HEALTH: Record<NoticeSeverity, "warning" | "danger" | null> = {
  info: null,
  warning: "warning",
  critical: "danger",
};

/* 한 쪽 20건. 운영자 화면이라 한 번에 훑는 양이 사용자 화면보다 크지만, 폴링이 5초마다
   같은 쪽을 다시 읽으므로 쪽이 커지면 그 비용이 계속 나간다 */
const NOTICE_PAGE_SIZE = 20;

export default function ConfigPage() {
  const canSuper = useCan("SUPER");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [severity, setSeverity] = useState<NoticeSeverity>("info");
  const [feeSymbol, setFeeSymbol] = useState("");
  const [makerFee, setMakerFee] = useState("0.0005");
  const [takerFee, setTakerFee] = useState("0.0005");
  const actions = useMemo(() => MARKET_ACTIONS, []);

  // 서버 parseFee와 같은 규칙으로 사전 검증한다. 요청·발급을 거친 뒤 실행 단계 400으로
  // 실패하면 승인 1건이 낭비되므로 입력 단계에서 막는다
  const makerErr = feeRateError(makerFee, "maker_fee");
  const takerErr = feeRateError(takerFee, "taker_fee");
  const feeEmpty = makerFee.trim() === "" && takerFee.trim() === "";
  const feeError = makerErr ?? takerErr ?? (feeEmpty ? "maker_fee 또는 taker_fee 중 하나 필요" : null);

  /* 공지는 철회만 되고 삭제되지 않아(V5 §7) 계속 쌓인다. 한 쪽씩 끊어 읽는다.
     i번째 칸이 i쪽을 여는 커서고 첫 쪽은 null이다(§2-6은 앞으로만 커서를 발급한다).
     loader가 ref로 잡히므로 폴링 틱도 보고 있는 쪽을 그대로 다시 읽는다 */
  const [noticePage, setNoticePage] = useState(0);
  const noticeCursors = useRef<(string | null)[]>([null]);

  const res = useAdminResource(
    async () => {
      const api = getAdminApi();
      const [markets, noticePageData] = await Promise.all([
        api.getConfigMarkets(),
        api.getNotices({ cursor: noticeCursors.current[noticePage] ?? null, limit: NOTICE_PAGE_SIZE }),
      ]);
      noticeCursors.current[noticePage + 1] = noticePageData.nextCursor;
      return { markets, notices: noticePageData };
    },
    // GET 2건 합성 + 승인 목록 폴링 동거 화면. 2초 주기는 관리 레이트리밋(60건/분) 초과라 5초로 완화
    { isSessionError },
  );
  const markets: ConfigMarket[] | null = res.data?.markets ?? null;
  const notices: Notice[] | null = res.data?.notices.data ?? null;
  const noticeHasMore = res.data?.notices.hasMore ?? false;
  const load = res.reload;

  /* 쪽을 옮기고 곧바로 다시 읽는다. loader가 최신 noticePage를 보도록 상태를 먼저 세운다 */
  const moveNoticePage = (target: number) => {
    setNoticePage(target);
  };
  useEffect(() => {
    void load();
    // load는 useAdminResource가 안정 참조로 준다. noticePage 변화에만 반응한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [noticePage]);

  /* 새 공지는 목록 맨 위에 낀다. 지나온 쪽 경계가 밀리므로 커서를 버리고 첫 쪽부터 다시 센다 */
  const reloadNoticesFromFirst = () => {
    noticeCursors.current = [null];
    if (noticePage === 0) void load();
    else setNoticePage(0);
  };

  const postNotice = async () => {
    if (!title.trim() || !body.trim()) {
      toast("제목과 내용 입력 필요", "error");
      return;
    }
    try {
      await getAdminApi().postNotice(title.trim(), body.trim(), severity);
      toast("공지 등록 완료", "info");
      setTitle("");
      setBody("");
      setSeverity("info");
      reloadNoticesFromFirst();
    } catch (e) {
      toast(e instanceof Error ? e.message : "등록 실패", "error");
    }
  };

  const toggleActive = async (n: Notice) => {
    try {
      await getAdminApi().updateNotice(n.id, { active: !n.active });
      toast(n.active ? "공지 내림 완료" : "공지 재게시 완료", "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "변경 실패", "error");
    }
  };

  const removeNotice = async (n: Notice) => {
    try {
      await getAdminApi().deleteNotice(n.id);
      toast("공지 소프트 삭제 완료", "info");
      await load();
    } catch (e) {
      toast(e instanceof Error ? e.message : "삭제 실패", "error");
    }
  };

  const marketCols: DataColumn<ConfigMarket>[] = [
    { key: "symbol", label: "마켓", width: "120px", render: (m) => m.symbol },
    // 이름 열을 1fr로 두면 슬랙을 전량 흡수해 행 한가운데 공백이 생긴다. 슬랙은 우측 제어 열로
    { key: "name", label: "이름", width: "200px", render: (m) => m.koreanName },
    {
      key: "state",
      label: "상태",
      width: "110px",
      // 정상(거래 중)은 잉크, 이탈 상태만 발화한다
      render: (m) =>
        m.state === "ACTIVE" ? (
          <span className={styles.stateText}>거래 중</span>
        ) : m.state === "HALTED" ? (
          <HealthPill health="warning" label="중지" />
        ) : (
          <span className={styles.neutralPill}>상장폐지</span>
        ),
    },
    /* 수수료 도메인 관용어라 번역하지 않고 음차한다. API 필드(maker_fee 등) 문구는 계약값이라 불변.
       표시는 비율이 아니라 퍼센트다. 계약값 0.00050000을 그대로 적으면 0이 여덟 자리라 열두 행이
       같은 모양이 되고 값이 달라져도 눈에 안 든다. 거래 화면도 같은 파생을 쓴다(0.0005 -> 0.05%).
       mulDecimal은 후행 0을 떼고 돌려주므로 별도 손질이 없다. float 왕복도 없다 */
    { key: "maker", label: "메이커", width: "96px", render: (m) => `${mulDecimal(m.makerFee, "100")}%` },
    { key: "taker", label: "테이커", width: "96px", render: (m) => `${mulDecimal(m.takerFee, "100")}%` },
    /* 주문 규칙 2열(§2 v2.35). 정지·수수료 판단에서 같이 읽히는 값인데 종전에는 고객 평면
       GET /markets 에만 있어 운영자가 다른 평면을 열어야 했다.
       NUMERIC 문자열을 그대로 받아 표시만 자릿수를 정리한다. float 왕복은 없다 */
    { key: "tick", label: "호가 단위", width: "110px", render: (m) => `${formatKRW(m.tickSize)}원` },
    { key: "minNotional", label: "최소 주문", width: "116px", render: (m) => `${formatKRW(m.minNotional)}원` },
    {
      key: "action",
      label: "제어",
      width: "minmax(120px, 0.6fr)",
      render: (m) =>
        m.state === "ACTIVE" ? (
          <RequestButton
            action="config.market.halt"
            payload={{ action: "config.market.halt", symbol: m.symbol }}
            label="중지 요청"
            onRequested={load}
            confirm={{
              title: "마켓 거래 중지 요청",
              description: "발급·실행 시 이 마켓의 신규 주문 차단",
              target: `${m.koreanName} (${m.symbol})`,
              blastRadius: "단일 마켓 · 미체결 주문 유지",
              tone: "danger",
              confirmLabel: "중지 요청",
            }}
          />
        ) : m.state === "HALTED" ? (
          <RequestButton
            action="config.market.resume"
            payload={{ action: "config.market.resume", symbol: m.symbol }}
            label="재개 요청"
            onRequested={load}
            confirm={{
              title: "마켓 거래 재개 요청",
              description: "발급·실행 시 이 마켓의 신규 주문 재허용",
              target: `${m.koreanName} (${m.symbol})`,
              blastRadius: "단일 마켓",
              confirmLabel: "재개 요청",
            }}
          />
        ) : (
          // DELISTED는 종국 상태라 재개 대상이 아니다(§6-D 409). 버튼을 노출하지 않는다
          <span className={styles.stateTextDim}>재개 불가</span>
        ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        title="마켓·공지"
        desc="중지·재개·수수료 변경은 1인 승인"
        actions={<ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />}
      />

      <PanelCard title="마켓 상태·수수료">
        {!canSuper && <PermissionNote>마켓 제어 권한(SUPER) 없음. 조회만 가능</PermissionNote>}
        {res.error ? (
          <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
        ) : !markets ? (
          <Skeleton variant="card" width="100%" height={200} />
        ) : (
          <div className={styles.staleRegion} data-stale={res.stale ? "" : undefined}>
            <DataTable columns={marketCols} rows={markets} rowKey={(m) => m.symbol} rowHeight={48} ariaLabel="마켓 설정" defaultAlign="center" />
          </div>
        )}
      </PanelCard>

      <PanelCard title="수수료 변경 요청" actions={<span className={styles.panelMeta}>1인 승인</span>}>
        {!canSuper && <PermissionNote>수수료 변경 권한(SUPER) 없음</PermissionNote>}
        <div className={styles.formRow}>
          <label className={styles.field}>
            <span>마켓</span>
            <select value={feeSymbol} onChange={(e) => setFeeSymbol(e.target.value)} disabled={!canSuper} aria-label="수수료 대상 마켓">
              <option value="">마켓 선택</option>
              {(markets ?? [])
                .filter((m) => m.state !== "DELISTED")
                .map((m) => (
                  <option key={m.symbol} value={m.symbol}>
                    {m.koreanName} ({m.symbol})
                  </option>
                ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>메이커</span>
            <input
              value={makerFee}
              onChange={(e) => setMakerFee(e.target.value)}
              inputMode="decimal"
              aria-label="메이커 수수료율"
              aria-invalid={makerErr ? true : undefined}
            />
          </label>
          <label className={styles.field}>
            <span>테이커</span>
            <input
              value={takerFee}
              onChange={(e) => setTakerFee(e.target.value)}
              inputMode="decimal"
              aria-label="테이커 수수료율"
              aria-invalid={takerErr ? true : undefined}
            />
          </label>
          {feeSymbol && feeError === null ? (
            <RequestButton
              action="config.market.fees"
              payload={{ action: "config.market.fees", symbol: feeSymbol, makerFee, takerFee }}
              label="변경 요청"
              onRequested={load}
              confirm={{
                title: "수수료 변경 요청",
                description: "발급·실행 시 이 마켓의 maker·taker 수수료율 갱신",
                target: feeSymbol,
                blastRadius: `maker ${makerFee} · taker ${takerFee}`,
                confirmLabel: "변경 요청",
              }}
            />
          ) : (
            <button type="button" className={styles.neutralBtn} disabled>
              변경 요청
            </button>
          )}
        </div>
        {feeError !== null && (
          <p className={styles.scaleErr} role="alert">
            {feeError}
          </p>
        )}
      </PanelCard>

      <PanelCard title="마켓 제어 승인 대기">
        <ApprovalList
          actions={actions}
          onChange={load}
          emptyLabel="대기 중인 마켓 제어 승인 없음"
          emptyDesc="중지·재개·수수료 요청이 들어오면 여기에서 발급·실행"
        />
      </PanelCard>

      <PanelCard title="공지 등록">
        {!canSuper && <PermissionNote>공지 등록 권한(SUPER) 없음</PermissionNote>}
        <div className={styles.noticeForm}>
          <label className={styles.field}>
            <span>제목</span>
            <input value={title} onChange={(e) => setTitle(e.target.value)} disabled={!canSuper} aria-label="공지 제목" />
          </label>
          <label className={styles.field}>
            <span>심각도</span>
            <select value={severity} onChange={(e) => setSeverity(e.target.value as NoticeSeverity)} disabled={!canSuper} aria-label="공지 심각도">
              {NOTICE_SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {NOTICE_SEVERITY_LABEL[s]}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.field}>
            <span>내용</span>
            <textarea value={body} onChange={(e) => setBody(e.target.value)} disabled={!canSuper} rows={3} aria-label="공지 내용" />
          </label>
          <button type="button" className={styles.primaryBtn} disabled={!canSuper} onClick={() => void postNotice()}>
            등록
          </button>
        </div>
      </PanelCard>

      <PanelCard title="공지 목록">
        {!notices ? (
          <Skeleton variant="card" width="100%" height={120} />
        ) : notices.length === 0 ? (
          <div className="empty-state empty-state--compact">
            <span className="empty-state__mark empty-state__mark--sm" aria-hidden="true" />
            <span className="empty-state__title">등록된 공지 없음</span>
            <span className="empty-state__desc">위 공지 등록 폼에서 게시하면 거래 화면 배너에 노출</span>
          </div>
        ) : (
          <ul className={styles.noticeList}>
            {notices.map((n) => {
              const sevHealth = SEVERITY_HEALTH[n.severity];
              return (
                <li key={n.id} className={styles.noticeItem} data-inactive={!n.active || undefined}>
                  <div className={styles.noticeHead}>
                    <span className={styles.noticeTitle}>{n.title}</span>
                    <span className={styles.noticeMeta}>
                      {sevHealth ? (
                        <span className="status-pill" data-health={sevHealth}>
                          {NOTICE_SEVERITY_LABEL[n.severity]}
                        </span>
                      ) : (
                        <span className={styles.neutralPill}>{NOTICE_SEVERITY_LABEL[n.severity]}</span>
                      )}
                      {!n.active && <span className={styles.neutralPill}>내림</span>}
                      {n.createdBy} · {formatTimeHMS(n.createdAt)}
                    </span>
                  </div>
                  <p className={styles.noticeBody}>{n.body}</p>
                  {canSuper && (
                    <div className={styles.approvalActions}>
                      <button type="button" className={styles.neutralBtn} onClick={() => void toggleActive(n)}>
                        {n.active ? "내리기" : "다시 게시"}
                      </button>
                      {n.active && (
                        <button type="button" className={styles.neutralBtn} onClick={() => void removeNotice(n)}>
                          소프트 삭제
                        </button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        <Pager
          page={noticePage}
          hasMore={noticeHasMore}
          onMove={moveNoticePage}
          ariaLabel="공지 목록 쪽 이동"
        />
      </PanelCard>
    </div>
  );
}
