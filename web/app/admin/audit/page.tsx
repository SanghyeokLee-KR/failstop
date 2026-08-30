"use client";

/**
 * admin_audit_log는 forbid_mutation 트리거로 수정·삭제가 막혀 읽기만 제공한다(§3-2).
 * 필터는 로드된 rows의 클라이언트 파생이라 범위 한계는 캡션이 고지한다.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { ACTION_GLOSS, NS_GLOSS } from "@/lib/admin/glossary";
import type { AuditEntry, AuditResult } from "@/lib/admin/types";
import Pager from "@/components/Pager";
import PanelCard from "@/components/PanelCard";
import Skeleton from "@/components/Skeleton";
import DataTable, { type DataColumn } from "@/components/DataTable";
import { PageHeader } from "@/components/admin/ui";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState, StaleBadge } from "@/components/admin/ResourceState";
import { formatKRW, formatTimeHMS } from "@/lib/format";
import styles from "@/components/admin/admin.module.css";

/* 주사 존 판독 리듬: 반복 접두(시분·네임스페이스)는 명도 강등, 변별 정보(초·동사)만 세운다 */
function renderTime(e: AuditEntry) {
  const hms = formatTimeHMS(e.at);
  return (
    <span className="num">
      <span className={styles.auditTimeHm}>{hms.slice(0, 5)}</span>
      <span className={styles.auditTimeSec}>{hms.slice(5)}</span>
    </span>
  );
}

/* 원문 코드 렌더. 주해 유무와 무관하게 감사자의 DB 대조 스캔을 보존한다 */
function renderActionCode(action: string) {
  const dot = action.lastIndexOf(".");
  if (dot === -1) return <span className={styles.auditVerb}>{action}</span>;
  return (
    <>
      <span className={styles.auditNs}>{action.slice(0, dot + 1)}</span>
      <span className={styles.auditVerb}>{action.slice(dot + 1)}</span>
    </>
  );
}

/* .denied 접미는 결과(거부)의 흔적이고 결과 열이 이미 전달한다. 주해 조회에서만 벗긴다 */
function glossOf(code: string): string | undefined {
  return ACTION_GLOSS[code] ?? ACTION_GLOSS[code.replace(/\.denied$/, "")];
}

function renderAction(e: AuditEntry) {
  // 합성 코드(approval.issue:chaos.inject, execute:...)는 head·tail을 각각 주해해 이어 붙인다.
  // 원문만 남기면 세션 행(주해 병기)과 열 질감이 갈라진다
  const colon = e.action.indexOf(":");
  const gloss =
    colon === -1
      ? glossOf(e.action)
      : (() => {
          const head = glossOf(e.action.slice(0, colon));
          const tail = glossOf(e.action.slice(colon + 1));
          return head && tail ? `${head} · ${tail}` : head ?? tail;
        })();
  // 미등재 코드는 원문 단독 폴백(억지 번역 금지)
  if (!gloss) return <span className={styles.auditAction}>{renderActionCode(e.action)}</span>;
  return (
    <span className={styles.auditAction}>
      {gloss}
      <span className={styles.auditCode}>{renderActionCode(e.action)}</span>
    </span>
  );
}

const COLUMNS: DataColumn<AuditEntry>[] = [
  { key: "at", label: "시각", width: "90px", render: renderTime },
  { key: "operator", label: "운영자", width: "minmax(120px, 1fr)", render: (e) => `${e.operatorName} (${e.operatorId})` },
  { key: "action", label: "액션", width: "minmax(160px, 1.2fr)", render: renderAction },
  {
    key: "target",
    label: "대상",
    width: "minmax(120px, 1fr)",
    // 기록 원문 그대로 표기한다(치환 금지, DB 문자열 대조 보존). 인증서 신원만 title 주해
    render: (e) =>
      e.target.startsWith("mTLS CN=") ? <span title="운영자 인증서 신원(mTLS CN)">{e.target}</span> : e.target,
  },
  {
    key: "result",
    label: "결과",
    width: "90px",
    // 정상(성공)은 잉크, 이탈만 pill로 발화한다. 색 단독 금지라 기호·라벨 병기
    render: (e) =>
      e.result === "OK" ? (
        <span className={styles.stateText}>성공</span>
      ) : e.result === "ERROR" ? (
        <span className="status-pill" data-health="warning">
          ▲ 오류
        </span>
      ) : (
        <span className="status-pill" data-health="danger">
          ■ 거부
        </span>
      ),
  },
  {
    key: "approval",
    label: "승인 ID",
    width: "minmax(120px, 1fr)",
    // 부재 플레이스홀더만 tertiary(비정보 장식). 실값은 기본 잉크 유지
    // 하이픈은 빈 칸을 메우는 장식이다. 낭독하면 "하이픈"이라는 소음만 남고 부재는
    // 빈 셀로 이미 전달된다. aria-hidden으로 빼면 대비 기준도 장식 기준(3:1)이 맞는다
    render: (e) =>
      e.approvalId ?? (
        <span className={styles.auditNil} aria-hidden="true">
          -
        </span>
      ),
  },
];

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";

/* 액션 코드의 첫 점 앞 네임스페이스. 필터 select의 파생 축.
   합성 코드(approval.issue:chaos.inject, execute:chaos.inject)의 콜론 뒤는 대상 한정자라 잘라낸다 */
function nsOf(action: string): string {
  const colon = action.indexOf(":");
  const code = colon === -1 ? action : action.slice(0, colon);
  const dot = code.indexOf(".");
  return dot === -1 ? code : code.slice(0, dot);
}

const RESULT_SEGMENTS: { value: "" | AuditResult; label: string }[] = [
  { value: "", label: "전체" },
  { value: "OK", label: "성공(OK)" },
  { value: "ERROR", label: "오류(ERROR)" },
  { value: "DENIED", label: "거부(DENIED)" },
];

/* 한 쪽 200건. 감사는 표를 훑는 화면이고 15초 폴링이 같은 쪽을 다시 읽으므로,
   쪽이 커지면 그 비용이 계속 나간다. 서버 상한은 500이다 */
const PAGE_SIZE = 200;

export default function AuditPage() {
  /* 감사 로그는 append-only라 영구 누적이다. i번째 칸이 i쪽을 여는 커서고
     첫 쪽은 null이다(§2-6은 앞으로만 커서를 발급한다) */
  const [page, setPage] = useState(0);
  const cursors = useRef<(string | null)[]>([null]);

  const load = useCallback(async () => {
    const res = await getAdminApi().getAudit({
      cursor: cursors.current[page] ?? null,
      limit: PAGE_SIZE,
    });
    cursors.current[page + 1] = res.nextCursor;
    return res;
  }, [page]);
  // 감사는 append-only라 3초 폴링이면 단조 증가 테이블을 계속 재수신한다.
  // 판독 용도에 15초면 충분하고 레이트리밋 예산도 아낀다
  const { data: pageData, loading, error, errorReason, stale, lastUpdatedAt, reload } = useAdminResource(load, { intervalMs: 15000, isSessionError });
  const rows = pageData?.data ?? null;
  const hasMore = pageData?.hasMore ?? false;

  useEffect(() => {
    void reload();
    // reload는 안정 참조다. 쪽 변화에만 반응한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const [opFilter, setOpFilter] = useState("");
  const [nsFilter, setNsFilter] = useState("");
  const [resultFilter, setResultFilter] = useState<"" | AuditResult>("");
  const [periodMin, setPeriodMin] = useState(0);

  // 정렬 고정이라 폴링으로 옵션이 늘어도 select 내부에서만 자란다(시프트 0)
  const operators = useMemo(() => {
    const byId = new Map<string, string>();
    for (const e of rows ?? []) if (!byId.has(e.operatorId)) byId.set(e.operatorId, e.operatorName);
    return [...byId.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [rows]);

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    for (const e of rows ?? []) set.add(nsOf(e.action));
    return [...set].sort();
  }, [rows]);

  const filtered = useMemo(() => {
    if (!rows) return [];
    // 세션 30분 데모 척도라 기간 비교는 클라이언트 시계로 충분하다. 폴링 리렌더가 기준 시각을 갱신한다
    const cutoff = periodMin > 0 ? Date.now() - periodMin * 60_000 : null;
    return rows.filter(
      (e) =>
        (opFilter === "" || e.operatorId === opFilter) &&
        (nsFilter === "" || nsOf(e.action) === nsFilter) &&
        (resultFilter === "" || e.result === resultFilter) &&
        (cutoff === null || new Date(e.at).getTime() >= cutoff),
    );
  }, [rows, opFilter, nsFilter, resultFilter, periodMin]);

  const hasFilter = opFilter !== "" || nsFilter !== "" || resultFilter !== "" || periodMin > 0;
  const resetFilters = () => {
    setOpFilter("");
    setNsFilter("");
    setResultFilter("");
    setPeriodMin(0);
  };

  return (
    <div className={styles.page}>
      <PageHeader
        title="감사 로그"
        desc="수정·삭제 불가 기록"
        actions={stale ? <StaleBadge since={lastUpdatedAt} /> : undefined}
      />
      <PanelCard title="관리 액션 감사">
        {loading ? (
          <Skeleton variant="card" width="100%" height={280} />
        ) : error || !rows ? (
          <AdminErrorState reason={errorReason} onRetry={reload} />
        ) : (
          <div className={styles.staleRegion} data-stale={stale ? "" : undefined}>
            <div className={styles.auditFilterRow}>
              <label className={styles.field}>
                <select value={opFilter} onChange={(e) => setOpFilter(e.target.value)} aria-label="운영자 필터">
                  <option value="">전체 운영자</option>
                  {operators.map(([id, name]) => (
                    <option key={id} value={id}>
                      {name} ({id})
                    </option>
                  ))}
                </select>
              </label>
              <label className={styles.field}>
                <select value={nsFilter} onChange={(e) => setNsFilter(e.target.value)} aria-label="액션 필터">
                  <option value="">전체 액션</option>
                  {namespaces.map((ns) => (
                    <option key={ns} value={ns}>
                      {NS_GLOSS[ns] ? `${ns} · ${NS_GLOSS[ns]}` : ns}
                    </option>
                  ))}
                </select>
              </label>
              {/* 동종 컨트롤(셀렉트 3종)을 붙이고 세그먼트는 뒤로. 기간만 세그먼트 너머에 고립되지 않게 한다 */}
              <label className={styles.field}>
                <select value={String(periodMin)} onChange={(e) => setPeriodMin(Number(e.target.value))} aria-label="기간 필터">
                  <option value="0">전체 기간</option>
                  <option value="5">최근 5분</option>
                  <option value="15">최근 15분</option>
                  <option value="30">최근 30분</option>
                </select>
              </label>
              <div className={styles.auditSeg} role="group" aria-label="결과 필터">
                {RESULT_SEGMENTS.map((seg) => (
                  <button
                    key={seg.value}
                    type="button"
                    className={styles.neutralBtn}
                    aria-pressed={resultFilter === seg.value}
                    onClick={() => setResultFilter(seg.value)}
                  >
                    {seg.label}
                  </button>
                ))}
              </div>
              {/* 클라이언트 필터의 범위 한계(로드분 한정)를 상시 고지한다. 등장·소멸 시프트 없음 */}
              <span className={`${styles.auditFilterCount} num`}>
                표시 {formatKRW(filtered.length)}건 · 불러온 {formatKRW(rows.length)}건 기준
              </span>
            </div>
            <div className={styles.auditTableReserve}>
              <DataTable
                columns={COLUMNS}
                rows={filtered}
                rowKey={(e) => e.id}
                // 비인터랙티브 판독 행이라 36px 밀도로 주사 존을 좁힌다(44px 히트 타깃 규칙은 조작 요소 대상)
                rowHeight={36}
                ariaLabel="관리 감사 로그"
                empty={
                  hasFilter && rows.length > 0 ? (
                    <div className="empty-state">
                      <span className="empty-state__title">조건에 맞는 기록 없음</span>
                      <button type="button" className={styles.neutralBtn} onClick={resetFilters}>
                        필터 초기화
                      </button>
                    </div>
                  ) : (
                    <div className="empty-state">
                      <span className="empty-state__title">기록된 액션 없음</span>
                      <span className="empty-state__desc">콘솔에서 일어나는 모든 관리 액션을 자동 적재</span>
                    </div>
                  )
                }
              />
            </div>

            {/* 필터는 이번 쪽 안에서만 거른다. 조건에 맞는 기록이 뒤쪽에 더 있을 수 있어
                필터가 걸려 있어도 쪽 이동은 살려둔다 */}
            <Pager
              page={page}
              hasMore={hasMore}
              onMove={setPage}
              ariaLabel="감사 기록 쪽 이동"
            />
          </div>
        )}
      </PanelCard>
    </div>
  );
}
