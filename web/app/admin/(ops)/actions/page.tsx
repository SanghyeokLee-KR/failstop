"use client";

/**
 * 조치 기록(/admin/actions, 관리자 설계 4-10-4). 누가(사람·AI·시스템) 무엇을 언제 바꿨고 결과가 어땠는지에 답한다. 머리 아래
 * 거르기 줄(행위자, 종류, 결과, 기간, 검색)과 오른쪽 끝의 CSV 내보내기, 그 아래 전폭 표다. 행을 누르면 조치 상세 모달(720px)이
 * 열린다. 실행 단추는 없다.
 *
 * 원천은 감사 기록이다. sim은 관리 어댑터의 감사와 판의 사건 이음을 합치고, http는 감사 로그(커서)와 조치 요청 목록을 읽는다.
 * 두 경로가 서버에 있으므로 http에서도 실측을 그린다. http는 받아 온 쪽에서만 거르므로 거른 결과가 전체가 아닐 수 있다고
 * 적는다. '지난 24시간 AI 실행 n회 · 되돌림 m회'는 AI 판단 화면의 평상 한 줄과 같은 규칙으로 같은 감사에서 센다.
 *
 * CSV는 지금 거른 행만, 보이는 열 그대로 브라우저에서 만든다. 파일 이름에 원천(sim·live)과 KST 시각을 넣는다.
 */

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { isAdminHttp } from "@/lib/admin/api";
import {
  ACTOR_WORD,
  actionsCsv,
  aiRecentOf,
  applyFilter,
  CATEGORY_ORDER,
  CATEGORY_WORD,
  csvFileName,
  DEFAULT_FILTER,
  type ActionFilter,
  type ActorFilter,
  type PeriodFilter,
  type ResultFilter,
} from "@/lib/admin/actionLog";
import { useOpsCenter, useOpsPick } from "@/lib/admin/opsCenter";
import { kstDay } from "@/lib/admin/timeText";
import type { ActionCategory } from "@/lib/admin/types";
import ActionDetail from "@/components/admin/actions/ActionDetail";
import ActionTable from "@/components/admin/actions/ActionTable";
import { useActionFeed } from "@/components/admin/actions/useActionFeed";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import OpsHead from "@/components/admin/control/OpsHead";
import { PanelHead, useClock } from "@/components/admin/control/parts";
import { Segmented } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import actStyles from "@/components/admin/actions/actions.module.css";

const ACTORS: { value: ActorFilter; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "OPERATOR", label: ACTOR_WORD.OPERATOR },
  { value: "AI", label: ACTOR_WORD.AI },
  { value: "SYSTEM", label: ACTOR_WORD.SYSTEM },
];
const PERIODS: { value: PeriodFilter; label: string }[] = [
  { value: "today", label: "오늘" },
  { value: "week", label: "7일" },
  { value: "all", label: "전체" },
];
const RESULTS: { value: ResultFilter; label: string }[] = [
  { value: "ALL", label: "모든 결과" },
  { value: "OK", label: "성공" },
  { value: "DENIED", label: "거부·오류" },
  { value: "REVERTED", label: "되돌림" },
];

const dayOf = (ms: number) => kstDay(ms);

/** 표의 골격. 거르기 줄 아래 표 자리에 행 여덟 줄이다 */
function TableSkeleton() {
  return (
    <div aria-busy="true">
      <span className="sr-only">조치 기록을 불러오는 중입니다</span>
      <div className={styles.stack} aria-hidden="true" style={{ gap: "var(--space-3)" }}>
        {Array.from({ length: 8 }, (_, i) => (
          <div key={i} style={{ display: "grid", gridTemplateColumns: "112px 13% 10% 24% 1fr 96px", gap: "var(--space-3)", alignItems: "center", height: 32 }}>
            {[70, 80, 60, 88, 72, 50].map((w, j) => (
              <i key={j} className={styles.bone} style={{ width: `${w}%`, height: 10 }} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 머리. 출처 표식이 판을 매 박자 읽으므로 이 조각만 판을 구독하고 표는 다시 그리지 않는다 */
function Head({ actions }: { actions: ReactNode }) {
  const { data } = useOpsCenter();
  return <OpsHead title="조치 기록" center={data} actions={actions} />;
}

/** 이 브라우저에서 파일을 만든다. 서버로 보내지 않는다 */
function download(text: string, name: string) {
  const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export default function ActionsPage() {
  const sim = !isAdminHttp();
  // 표는 sim의 다시 읽기 신호(판의 마지막 기록)만 구독한다. 판 전체를 구독하면 표가 매 박자 다시 그려진다
  const signal = useOpsPick((c) => (sim && c?.incidents.state === "READY" ? c.incidents.data.events[0]?.id ?? null : null));
  // 사건 칸은 사건 이름을 적는다. 사건 목록은 사건이 열리고 닫힐 때만 새 참조다
  const incidents = useOpsPick((c) => (c?.incidents.state === "READY" ? c.incidents.data.incidents : null));
  const incidentTitle = useMemo(() => {
    const byId = new Map((incidents ?? []).map((i) => [i.id, i.title]));
    return (id: string) => byId.get(id) ?? null;
  }, [incidents]);
  const feed = useActionFeed(signal);
  const now = useClock(30_000);
  const [filter, setFilter] = useState<ActionFilter>(DEFAULT_FILTER);
  const [open, setOpen] = useState<string | null>(null);

  // AI 판단 화면의 '조치 기록에서 보기'(?actor=AI)와 이력 행(?id=)이 거르기와 상세를 연다
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const actor = q.get("actor");
    if (actor === "AI" || actor === "OPERATOR" || actor === "SYSTEM") setFilter((f) => ({ ...f, actor }));
    // 네트워크·보안의 관리 접근 기록이 ?category=SESSION 으로 세션·승인 종류를 연다
    const category = q.get("category");
    if (category && (CATEGORY_ORDER as string[]).includes(category)) setFilter((f) => ({ ...f, category: category as ActionCategory, period: "all" }));
    const id = q.get("id");
    if (id) setOpen(id);
  }, []);

  const rows = feed.rows;
  const shown = useMemo(() => (rows ? applyFilter(rows, filter, now, dayOf) : []), [rows, filter, now]);
  const recent = useMemo(() => (rows ? aiRecentOf(rows, now) : null), [rows, now]);
  const set = <K extends keyof ActionFilter>(k: K, v: ActionFilter[K]) => setFilter((f) => ({ ...f, [k]: v }));
  const dirty = JSON.stringify(filter) !== JSON.stringify(DEFAULT_FILTER);
  const partial = feed.feed?.partial ?? false;

  const exportCsv = () => {
    if (shown.length === 0) return;
    download(actionsCsv(shown), csvFileName(feed.feed?.origin ?? (sim ? "SIM" : "LIVE"), Date.now()));
  };

  return (
    <div className={adminStyles.page}>
      <Head actions={<ResourceStamp lastUpdatedAt={feed.res.lastUpdatedAt} stale={feed.res.stale} onReload={feed.res.reload} />} />

      <section className={styles.panel} aria-labelledby="act-table">
        <PanelHead
          id="act-table"
          title="모든 조치"
          meta={
            rows ? (
              <span className="num">
                받아 온 {rows.length}건 · 거른 결과 {shown.length}건{partial ? " · 받아 온 쪽에서만 거릅니다" : ""}
              </span>
            ) : undefined
          }
        />
        <div className={actStyles.filterBar}>
        <div className={actStyles.filters} role="group" aria-label="조치 기록 거르기">
          <Segmented items={ACTORS} value={filter.actor} onChange={(v) => set("actor", v)} ariaLabel="행위자" semantics="pressed" size="sm" />
          <label className={actStyles.filterGroup}>
            <select id="act-cat" aria-label="종류" className={actStyles.select} value={filter.category} onChange={(e) => set("category", e.target.value as ActionCategory | "ALL")}>
              <option value="ALL">모든 종류</option>
              {CATEGORY_ORDER.map((c) => (
                <option key={c} value={c}>
                  {CATEGORY_WORD[c]}
                </option>
              ))}
            </select>
          </label>
          <label className={actStyles.filterGroup}>
            <select id="act-result" aria-label="결과" className={actStyles.select} value={filter.result} onChange={(e) => set("result", e.target.value as ResultFilter)}>
              {RESULTS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          </label>
          <Segmented items={PERIODS} value={filter.period} onChange={(v) => set("period", v)} ariaLabel="기간" semantics="pressed" size="sm" />
          <label className={actStyles.filterGroup}>
            <input
              type="search"
              className={actStyles.search}
              aria-label="조치 기록 검색 · 코드, 주해, 대상, 행위자, 승인 티켓, 사건"
              placeholder="코드·대상·행위자 검색"
              value={filter.query}
              onChange={(e) => set("query", e.target.value)}
            />
          </label>
        </div>
        <span className={actStyles.filterEnd}>
          <span className={actStyles.capNote}>{partial ? "받아 온 행만" : "거른 행만"}</span>
          <button type="button" className={adminStyles.neutralBtn} disabled={shown.length === 0} onClick={exportCsv}>
            CSV 내보내기
          </button>
        </span>
        </div>
        {recent && (
          <p className={actStyles.summary}>
            <span>
              지난 24시간 AI 실행 <b className="num">{recent.runs}회</b> · 되돌림 <b className="num">{recent.reverts}회</b>
            </span>
            <span className={actStyles.summaryNote}>AI 판단 화면과 같은 감사 기록에서 셉니다{partial ? " · 받아 온 쪽 기준" : ""}</span>
          </p>
        )}

        {feed.res.loading ? (
          <TableSkeleton />
        ) : feed.res.error || rows === null ? (
          <AdminErrorState reason={feed.res.errorReason} onRetry={feed.res.reload} />
        ) : shown.length === 0 ? (
          <div className={styles.empty}>
            <span className={styles.emptyMark} aria-hidden="true" />
            조건에 맞는 조치 없음
            {dirty && (
              <button type="button" className={adminStyles.neutralBtn} style={{ marginLeft: "auto" }} onClick={() => setFilter(DEFAULT_FILTER)}>
                거르기 초기화
              </button>
            )}
          </div>
        ) : (
          <ActionTable rows={shown} now={now} onOpen={setOpen} incidentTitle={incidentTitle} />
        )}
        {feed.hasMore && (
          <div className={actStyles.moreRow}>
            <span className={actStyles.capNote}>감사 로그는 500줄씩 나눠 읽습니다</span>
            <button type="button" className={adminStyles.neutralBtn} disabled={feed.loadingMore} onClick={feed.more}>
              {feed.loadingMore ? "읽는 중" : "더 불러오기"}
            </button>
          </div>
        )}
      </section>

      {open && rows && (
        <ActionDetail id={open} rows={rows} approvals={feed.feed?.approvals ?? []} sim={sim} partial={partial} onClose={() => setOpen(null)} />
      )}
    </div>
  );
}
