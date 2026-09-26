"use client";

/**
 * AI 판단 화면의 판단 기록(관리자 설계 4-10-2, 레이아웃 §7). 왼쪽 칸의 맨 위 판이다. 지금 판단은 표 위 한 줄로 줄이고, 대기 조치안이
 * 있으면 그 카드를 그 아래에 둔다. 표는 지난 24시간의 판단 한 건이 한 줄이고 시각, 결과, 확신도, 정책 판정, 처리 결과를 싣는다.
 * 결과와 기간으로 거르고, 줄을 누르면 AI 판단 상세(판단 과정)가 열린다. 관제센터의 AI 관제는 최근 판단 몇 줄만 싣는 판이라
 * 이 화면은 하루치 판단을 찾는 표를 따로 둔다.
 */

import { useMemo, useState } from "react";
import { limitsOf } from "@/lib/admin/aiPolicy";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { filterJudgments, JUDGMENT_FILTERS, JUDGMENT_HOURS, judgmentConfidence, judgmentGroup, judgmentMark, policyVerdict, type JudgmentGroup } from "@/lib/admin/judgmentLog";
import { joinDots } from "@/lib/admin/text";
import { kstDay, mdhm } from "@/lib/admin/timeText";
import type { AiDesk, AiJudgment, OpsSection } from "@/lib/admin/types";
import DataTable, { type DataColumn } from "@/components/DataTable";
import StatusShape from "../StatusShape";
import { Segmented } from "../ui";
import { confidenceWord, ProposalCard, splitLead } from "../control/AiPanel";
import { AiMark, hms, NotConnected, PanelHead, sectionSource, useClock } from "../control/parts";
import styles from "./ai.module.css";

function Outcome({ outcome }: { outcome: string }) {
  const m = judgmentMark(outcome);
  return (
    <span className={styles.outcome} data-tone={m.tone}>
      {m.kind === "ai" ? <AiMark size={12} /> : <StatusShape kind={m.kind} className={styles.outcomeShape} />}
      <span>{outcome}</span>
    </span>
  );
}

export default function JudgmentLog({ section, onChanged }: { section: OpsSection<AiDesk> | null; onChanged: () => void }) {
  const now = useClock(10_000);
  const [group, setGroup] = useState<JudgmentGroup | "ALL">("ALL");
  const [hours, setHours] = useState<(typeof JUDGMENT_HOURS)[number]>(24);
  const ai = section?.state === "READY" ? section.data : null;
  const history = ai?.history ?? [];
  const today = kstDay(now);
  const rows = useMemo(() => filterJudgments(history, group, hours, now), [history, group, hours, now]);
  const counts = useMemo(() => {
    const within = filterJudgments(history, "ALL", hours, now);
    const c: Record<string, number> = { ALL: within.length };
    for (const j of within) c[judgmentGroup(j.outcome)] = (c[judgmentGroup(j.outcome)] ?? 0) + 1;
    return c;
  }, [history, hours, now]);
  const limits = limitsOf(ai?.limits);
  const open = ai ? ai.proposals.filter((p) => p.status === "PROPOSED") : [];
  const [lead] = ai ? splitLead(ai.situation) : [""];
  const proposalsInDay = filterJudgments(history, "PROPOSAL", 24, now).length;

  // 칸의 최소 폭 합(간격 포함 528px)은 1280 창의 표 폭(534px) 안이다. 넓은 창에서는 글 칸이 비율로 늘어난다
  const columns: DataColumn<AiJudgment>[] = [
    {
      key: "at",
      label: "시각",
      width: "72px",
      align: "right",
      render: (j) => <span className="num">{kstDay(Date.parse(j.at)) === today ? hms(j.at) : mdhm(j.at)}</span>,
    },
    { key: "outcome", label: "결과", width: "minmax(104px, 1.3fr)", render: (j) => <Outcome outcome={j.outcome} /> },
    {
      key: "conf",
      label: "확신도",
      width: "72px",
      align: "right",
      render: (j) => {
        const c = judgmentConfidence(j);
        return c === null ? <span className={styles.none} aria-hidden="true">-</span> : <span className="num">{c}% · {confidenceWord(c, limits.confidencePct)}</span>;
      },
    },
    {
      key: "policy",
      label: "정책 판정",
      width: "minmax(96px, 1.1fr)",
      render: (j) => {
        const v = policyVerdict(j);
        if (v === null) return <span className={styles.none} title={j.lazy ? "판단 과정은 상세를 열 때 서버에서 읽습니다" : undefined} aria-hidden="true">-</span>;
        return <span data-blocked={v.blocked ? "" : undefined} className={styles.verdict}>{v.word}</span>;
      },
    },
    {
      key: "handled",
      label: "처리 결과",
      width: "minmax(112px, 1.2fr)",
      render: (j) => (j.handled ? <span>{j.handled}</span> : <span className={styles.none} aria-hidden="true">-</span>),
    },
  ];

  return (
    <section className={styles.log} aria-labelledby="ai-log">
      <PanelHead
        id="ai-log"
        title={
          <span className={styles.logTitle}>
            <span className={styles.logGlyph} aria-hidden="true">
              <AiMark size={16} />
            </span>
            판단 기록
          </span>
        }
        meta={
          ai ? (
            <span className="num">
              지난 24시간 {filterJudgments(history, "ALL", 24, now).length}건 · 조치안 낸 판단 {proposalsInDay}건
            </span>
          ) : undefined
        }
      />
      {section !== null && section.state !== "READY" ? (
        <NotConnected what="AI 판단" source={sectionSource(section)} />
      ) : ai === null ? (
        <p className={styles.logEmpty}>판단 기록을 불러오는 중입니다</p>
      ) : (
        <>
          <div className={styles.now}>
            <span className={styles.nowKey}>지금</span>
            <span className={`${styles.nowAt} num`}>{ai.judgedAt ? hms(ai.judgedAt) : "판단 전"}</span>
            {ai.history[0] && <Outcome outcome={ai.history[0].outcome} />}
            <span className={styles.nowText} title={ai.situation}>
              {joinDots(lead)}
            </span>
          </div>
          {open.length > 0 && (
            <div className={styles.pending}>
              {open.map((p, i) => (
                <ProposalCard key={p.id} p={p} index={i + 1} ai={ai} limits={limits} onChanged={onChanged} onEvidence={() => {}} fresh={false} />
              ))}
            </div>
          )}
          <div className={styles.filters}>
            <Segmented
              items={JUDGMENT_FILTERS.map((f) => ({
                value: f.id,
                label: (
                  <>
                    {f.label} <span className="num">{counts[f.id] ?? 0}</span>
                  </>
                ),
              }))}
              value={group}
              onChange={setGroup}
              ariaLabel="판단 결과 거르기"
              semantics="pressed"
              size="sm"
            />
            <Segmented
              items={JUDGMENT_HOURS.map((h) => ({ value: String(h), label: `${h}시간` }))}
              value={String(hours)}
              onChange={(v) => setHours(Number(v) as (typeof JUDGMENT_HOURS)[number])}
              ariaLabel="판단 기간"
              semantics="pressed"
              size="sm"
            />
          </div>
          <div className={styles.tableBox}>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(j) => j.id}
              rowHeight={44}
              onRowClick={(j) => useOpsDetail.getState().openJudgment(j.id)}
              rowAriaLabel={(j) => `${hms(j.at)} ${j.outcome}. 누르면 판단 과정을 엽니다`}
              ariaLabel="판단 기록"
              empty={<p className={styles.logEmpty}>이 조건의 판단 기록이 없습니다</p>}
            />
          </div>
          <p className={styles.logFoot}>AI는 5초마다 판단하고 결과가 바뀔 때만 기록을 남깁니다. 줄을 누르면 판단 과정이 열립니다.</p>
        </>
      )}
    </section>
  );
}
