"use client";

/**
 * 보고서(/admin/reports, 관리자 설계 4-7). bot-1 보고 서비스가 Teams로 보낸 일일·사건 보고를 읽는다.
 * 관제 콘솔은 보고를 만들거나 보내지 않고 읽기만 한다. 목록에서 한 건을 고르면 오른쪽에 같은 정보 구조의
 * 상세와 'Teams로 보낸 카드' 미리보기, 전송 기록이 선다.
 *
 * 원천은 sim이면 예시 세 건(일일 정상, 일일 주의, 사건 보고의 자동 되돌림)이고 화면이 '시뮬레이션'을
 * 표시한다. http는 보고 서비스 읽기 프록시(서버 계약 초안)가 생기기 전이라 원천 연결 전으로 그린다.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { AdminError, getAdminApi, isAdminHttp } from "@/lib/admin/api";
import { INTERVENTION_SHAPE, JOB_WORD, KIND_WORD } from "@/lib/admin/reports";
import { kst } from "@/lib/admin/timeText";
import type { OpsReport, ReportDesk, ReportKind } from "@/lib/admin/types";
import Skeleton from "@/components/Skeleton";
import { useAdminResource } from "@/components/admin/useAdminResource";
import { AdminErrorState } from "@/components/admin/ResourceState";
import ResourceStamp from "@/components/admin/ResourceStamp";
import { NotConnected } from "@/components/admin/control/parts";
import { DeliveryLog, ReportDetail, TeamsCardPreview } from "@/components/admin/reports/ReportView";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/reports/reports.module.css";

const isSessionError = (e: unknown) => e instanceof AdminError && e.code === "jwt_verification";
type Filter = "ALL" | ReportKind;
const FILTERS: { id: Filter; label: string }[] = [
  { id: "ALL", label: "전체" },
  { id: "daily", label: "일일" },
  { id: "incident", label: "사건" },
];

function DeliverySummary({ desk }: { desk: ReportDesk }) {
  const d = desk.delivery;
  const lastDaily = desk.reports.find((r) => r.kind === "daily") ?? null;
  const lastWord = lastDaily ? JOB_WORD[lastDaily.status] : null;
  return (
    <dl className={styles.summary} aria-label="Teams 전송 요약">
      <div className={styles.cell}>
        <dt className={styles.cellKey}>마지막 일일 보고</dt>
        <dd className={`${styles.cellValue} num`}>
          {d.lastDailySentAt ? kst(d.lastDailySentAt).slice(5, 16) : "없음"}
          {d.lastDailySentAt && <small>KST</small>}
        </dd>
        <dd className={styles.cellSub}>
          {lastWord ? (
            <>
              <span className={styles.shape} data-tone={lastWord.tone} aria-hidden="true">
                {lastWord.shape}{" "}
              </span>
              {lastWord.word} · {lastDaily?.intervention.priority} {lastDaily?.intervention.label}
            </>
          ) : (
            "보낸 일일 보고 없음"
          )}
        </dd>
      </div>
      <div className={styles.cell}>
        <dt className={styles.cellKey}>전송 대기</dt>
        <dd className={`${styles.cellValue} num`}>
          {d.pending}
          <small>건</small>
        </dd>
        <dd className={styles.cellSub}>재시도 대기 포함</dd>
      </div>
      <div className={styles.cell}>
        <dt className={styles.cellKey}>전송 실패</dt>
        <dd className={`${styles.cellValue} num`}>
          {d.dead > 0 && (
            <span className={styles.shape} data-tone="danger" aria-hidden="true">
              ■{" "}
            </span>
          )}
          {d.dead}
          <small>건</small>
        </dd>
        <dd className={styles.cellSub}>다시 보내지 않는 실패</dd>
      </div>
      <div className={styles.cell}>
        <dt className={styles.cellKey}>다음 일일 보고</dt>
        <dd className={`${styles.cellValue} num`}>
          {d.nextDailyAt ? kst(d.nextDailyAt).slice(5, 16) : "예약 없음"}
          {d.nextDailyAt && <small>KST</small>}
        </dd>
        <dd className={styles.cellSub}>{d.nextDailyAt ? "지난 24시간 구간 · timer 예약" : "timer 확인 필요"}</dd>
      </div>
    </dl>
  );
}

function ReportRow({ r, selected, onPick }: { r: OpsReport; selected: boolean; onPick: () => void }) {
  const shape = r.transition === "resolved" ? { shape: "●", tone: "success" as const } : INTERVENTION_SHAPE[r.intervention.code];
  const job = JOB_WORD[r.status];
  return (
    <button type="button" className={styles.row} aria-current={selected ? "true" : undefined} onClick={onPick}>
      <span className={`${styles.rowShape} ${styles.shape}`} data-tone={shape.tone} aria-hidden="true">
        {shape.shape}
      </span>
      <span className={styles.rowTitle}>{r.title}</span>
      <span className={`${styles.rowPriority} num`}>{r.intervention.priority}</span>
      <span className={styles.rowMeta}>
        <span>{KIND_WORD[r.kind]}</span>
        <span className="num">{kst(r.createdAt).slice(5)}</span>
        <span>
          <span className={styles.shape} data-tone={job.tone} aria-hidden="true">
            {job.shape}{" "}
          </span>
          {job.word}
        </span>
      </span>
    </button>
  );
}

export default function ReportsPage() {
  const load = useCallback(() => getAdminApi().getReports(), []);
  const res = useAdminResource(load, { cadence: "idle", isSessionError });
  const [filter, setFilter] = useState<Filter>("ALL");
  const [picked, setPicked] = useState<string | null>(null);
  const sim = !isAdminHttp();

  // 다른 화면이 ?id=로 한 건을 가리키면 그 보고를 먼저 고른다
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("id");
    if (id) setPicked(id);
  }, []);

  const desk = res.data?.state === "READY" ? res.data.data : null;
  const list = useMemo(() => (desk ? desk.reports.filter((r) => filter === "ALL" || r.kind === filter) : []), [desk, filter]);
  const current = list.find((r) => r.id === picked) ?? list[0] ?? null;

  const onListKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    if (!current) return;
    e.preventDefault();
    const i = list.indexOf(current);
    const next = list[(i + (e.key === "ArrowDown" ? 1 : list.length - 1)) % list.length];
    setPicked(next.id);
    e.currentTarget.querySelectorAll<HTMLButtonElement>("button")[list.indexOf(next)]?.focus();
  };

  return (
    <div className={adminStyles.page}>
      <header className={adminStyles.pageHead}>
        <div>
          <div className={controlStyles.titleRow}>
            <h1 className={adminStyles.pageTitle}>보고서</h1>
            {res.data?.state === "READY" && sim && <span className={controlStyles.sourceChip} data-kind="sim">시뮬레이션 · 예시</span>}
            {res.data?.state === "NOT_CONNECTED" && (
              <span className={controlStyles.sourceChip} data-kind="none">
                <span aria-hidden="true">◌ </span>원천 연결 전
              </span>
            )}
          </div>
          <p className={controlStyles.headDesc}>bot-1 보고 서비스가 Teams로 보낸 일일·사건 보고입니다. 관제 콘솔은 보고를 읽기만 합니다</p>
        </div>
        <div className={adminStyles.pageActions}>
          <ResourceStamp lastUpdatedAt={res.lastUpdatedAt} stale={res.stale} onReload={res.reload} />
        </div>
      </header>

      {res.loading ? (
        <Skeleton variant="card" width="100%" height={480} />
      ) : res.error || !res.data ? (
        <AdminErrorState reason={res.errorReason} onRetry={res.reload} />
      ) : res.data.state === "NOT_CONNECTED" ? (
        <NotConnected what="운영 보고" source={res.data.source} />
      ) : desk ? (
        <div className={adminStyles.pageStack}>
          <DeliverySummary desk={desk} />
          <div className={styles.layout}>
            <section className={styles.listPanel} aria-labelledby="report-list">
              <div className={styles.listHead}>
                <h2 id="report-list" className={styles.listTitle}>
                  보고 <span className={`${controlStyles.count} num`}>{list.length}</span>
                </h2>
                <span className={controlStyles.switch} role="group" aria-label="보고 종류">
                  {FILTERS.map((f) => (
                    <button key={f.id} type="button" className={controlStyles.switchBtn} data-size="xs" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
                      {f.label}
                    </button>
                  ))}
                </span>
              </div>
              {list.length === 0 ? (
                <div className={controlStyles.empty} style={{ margin: "var(--space-4)" }}>
                  <span className={controlStyles.emptyMark} aria-hidden="true" />
                  고른 종류의 보고 없음
                </div>
              ) : (
                <div className={styles.list} role="group" onKeyDown={onListKey} aria-label="보고 목록 · 위아래 화살표로 고름">
                  {list.map((r) => (
                    <ReportRow key={r.id} r={r} selected={current?.id === r.id} onPick={() => setPicked(r.id)} />
                  ))}
                </div>
              )}
            </section>
            {current && (
              <div className={styles.detailWrap}>
                <div className={styles.detail}>
                  <ReportDetail r={current} sim={sim} />
                  <div className={styles.aside}>
                    <TeamsCardPreview r={current} />
                    <DeliveryLog r={current} />
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
