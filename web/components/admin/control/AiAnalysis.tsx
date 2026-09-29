"use client";

/**
 * AI 관제 패널의 원인 분석과 오류 로그(API 설계 §6-F v2.65, 디자인 시스템 §12-9). 둘 다 조회 전용이라 단추가 없다.
 *
 * 원인 분석은 코드가 먼저 계산한 부하 판정(규칙)과 모델의 판정을 나란히 두고, 가설 아래에는 모델 문장 속 숫자가 아니라 서버가
 * 근거 묶음에서 붙인 값을 무채 근거 칩으로 적는다. 부하 판정은 건강 상태가 아니라 ●▲■를 쓰지 않는다. 오류 로그는 관리 서버가
 * 1분마다 모은 앱 WARN·ERROR 묶음이고, 대표 문장과 AI 문장은 링크나 서식 없이 평문으로만 그린다.
 */

import { Fragment } from "react";
import { citationParts, lackingReason, LOAD_WORD, loadFacts, seenWord } from "@/lib/admin/causeText";
import { joinDots } from "@/lib/admin/text";
import type { CauseAnalysis, CauseCitation, LoadVerdict, LogDesk, LogGroup, OpsSection } from "@/lib/admin/types";
import KeepNames from "../KeepNames";
import { AiMark, hms, SEVERITY_SHAPE, Shape, Unavailable } from "./parts";
import styles from "./control.module.css";

/** 패널에 싣는 묶음 수. 나머지는 '외 n개'로 센다 */
const LOGS_SHOWN = 3;

/** AI가 말한 줄의 표지. 표지만 AI 강조색이고, 옆 글이 AI라고 적지 않는 줄은 낭독 글(label)이 뜻을 싣는다 */
function AiSign({ label }: { label?: string }) {
  return (
    <>
      <span className={styles.aiInline} aria-hidden="true">
        <AiMark size={12} />
      </span>
      {label && <span className="sr-only">{label} </span>}
    </>
  );
}

/** 규칙 판정 줄과, 모델의 판정이 있으면 그 줄. 둘이 달라도 둘 다 보인다 */
function LoadLines({ load }: { load: LoadVerdict }) {
  return (
    <div className={styles.loadVerdict}>
      <p className={styles.loadLine}>
        <span className={styles.loadKey}>규칙 판정</span> <b className={styles.verdict}>{LOAD_WORD[load.pattern]}</b>
        {loadFacts(load).map((f) => (
          <Fragment key={f}>
            {"\u00a0· "}
            <span className={styles.nowrap}>{f}</span>
          </Fragment>
        ))}
      </p>
      {load.aiPattern && (
        <p className={styles.loadLine}>
          <AiSign />
          <span className={styles.loadKey}>AI 판정</span> <b className={styles.verdict}>{LOAD_WORD[load.aiPattern]}</b>
          {load.aiReason && (
            <>
              {"\u00a0· "}
              {joinDots(load.aiReason)}
            </>
          )}
        </p>
      )}
    </div>
  );
}

const SEVERITY_TONE = { critical: "danger", warning: "warning", info: "info" } as const;

function CiteChip({ c }: { c: CauseCitation }) {
  const p = citationParts(c);
  return (
    <span className={styles.citeChip} title={p.before === null ? undefined : "값은 기준 시각부터의 평균, 이전은 기준 시각 앞의 평균입니다"}>
      {p.severity && (
        <>
          <Shape tone={SEVERITY_TONE[p.severity]}>{SEVERITY_SHAPE[p.severity]}</Shape>{" "}
        </>
      )}
      {c.label} <b className="num">{p.value}</b>
      {p.before !== null && (
        <span className={styles.citeMeta}>
          {"\u00a0· 이전\u00a0"}
          <span className="num">{p.before}</span>
          {p.change !== null && (
            <>
              {"\u00a0· "}
              <span className="num">{p.change}</span>
            </>
          )}
        </span>
      )}
      {c.at && (
        <span className={styles.citeMeta}>
          {"\u00a0· "}
          {p.atWord && `${p.atWord}\u00a0`}
          <span className="num">{hms(c.at)}</span>
        </span>
      )}
    </span>
  );
}

/**
 * 원인 분석 묶음. 부하 판정, 순위 있는 가설과 근거 인용, 근거 부족, 수집 필요 순서다. 모델이 실패했으면 가설 대신 실패 줄을
 * 두고, 코드가 계산한 부하 판정과 수집 필요 줄은 그대로 둔다
 */
export function CauseSection({ analysis: a }: { analysis: CauseAnalysis }) {
  const lacking = lackingReason(a);
  return (
    <div className={styles.aiSection}>
      <div className={styles.subHead}>
        <span>원인 분석</span>
        {(a.analyzedAt || a.model) && (
          <span className={styles.subMeta}>
            {a.analyzedAt && <span className="num">{hms(a.analyzedAt)}</span>}
            {a.analyzedAt && a.model && "\u00a0· "}
            {a.model}
          </span>
        )}
      </div>
      {a.load && <LoadLines load={a.load} />}
      {a.state !== "READY" ? (
        <p className={styles.quietLine}>
          <b>AI 분석 실패</b>({a.reason})
        </p>
      ) : (
        a.hypotheses.length > 0 && (
          <ol className={styles.causeList} aria-label="원인 가설">
            {a.hypotheses.map((h, i) => (
              <li key={i}>
                <span className={`${styles.causeNo} num`} aria-hidden="true">
                  {i + 1}
                </span>
                <p className={styles.causeText}>{joinDots(h.cause)}</p>
                {h.citations.length > 0 && (
                  <div className={styles.citeChips}>
                    {h.citations.map((c, j) => (
                      <CiteChip key={`${c.id}:${j}`} c={c} />
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ol>
        )
      )}
      {lacking && (
        <p className={styles.quietLine}>
          <b>근거 부족</b>
          {"\u00a0· "}
          {joinDots(lacking)}
        </p>
      )}
      {a.missing.length > 0 && (
        <p className={styles.quietLine} title={a.missing.map((m) => `${m.label}: ${m.reason}`).join("\n")}>
          수집 필요: {a.missing.map((m) => m.label).join(", ")}
        </p>
      )}
    </div>
  );
}

function LogRow({ g, polledAt }: { g: LogGroup; polledAt: string | null }) {
  const error = g.level === "ERROR";
  return (
    <li className={styles.logRow}>
      <Shape tone={error ? "danger" : "warning"}>{error ? "■" : "▲"}</Shape>
      <div className={styles.logBody}>
        <div className={styles.logHead}>
          <span className={styles.logName} title={g.exception ? `${g.exception} · ${g.logger}` : g.logger}>
            <span className="sr-only">{error ? "ERROR" : "WARN"} </span>
            {g.exception ?? (g.logger || "이름 미확인")}
            {g.exception && <span className={styles.logLogger}>{`\u00a0· ${g.logger}`}</span>}
          </span>
          <span className={styles.logCount}>{g.count === null ? "건수 미수집" : <><b className="num">{g.count}</b>건</>}</span>
          <span className={`${styles.logMeta} ${styles.nowrap} num`}>
            처음 {seenWord(g.firstSeen, polledAt)}
            {"\u00a0· "}마지막 {seenWord(g.lastSeen, polledAt)}
          </span>
          {g.nodes.length > 0 && (
            <span className={styles.logMeta}>
              <KeepNames text={g.nodes.join(", ")} />
            </span>
          )}
        </div>
        <p className={styles.logMessage} title={g.exceptionMessage ? `${g.message}\n원인 예외: ${g.exceptionMessage}` : g.message}>
          {g.message}
        </p>
        {g.finding && (
          <p className={styles.logFinding}>
            <AiSign label="AI 발견" />
            {joinDots(g.finding.summary)}
            {g.finding.cause && <> 원인 후보: {joinDots(g.finding.cause)}</>}
          </p>
        )}
      </div>
    </li>
  );
}

/**
 * 오류 로그 묶음. 연결 전은 점선 한 줄, 불러오기 실패는 작은 실패 면, 묶음이 없으면 평상 한 줄이다(§12-8). 묶음은 한 판의
 * 순서대로 세 개까지 싣고, 수집에 실패한 노드는 따로 한 줄로 적는다
 */
export function ErrorLogSection({ logs }: { logs: OpsSection<LogDesk> }) {
  const d = logs.state === "READY" ? logs.data : null;
  const win = d?.windowMinutes ?? null;
  const shown = d ? d.groups.slice(0, LOGS_SHOWN) : [];
  const rest = d ? d.groups.length - shown.length + d.more : 0;
  return (
    <div className={styles.aiSection}>
      <div className={styles.subHead}>
        <span>오류 로그{win !== null && ` · 최근 ${win}분`}</span>
        {d && (
          <span className={styles.subMeta}>
            ERROR <b className="num">{d.errors ?? "미수집"}</b>
            {"\u00a0· "}WARN <b className="num">{d.warnings ?? "미수집"}</b>
          </span>
        )}
        {d?.polledAt && <span className={`${styles.subMeta} num`}>수집 {hms(d.polledAt)}</span>}
      </div>
      {logs.state === "NOT_CONNECTED" && (
        <p className={styles.gapLine}>
          <Shape tone="off">◌</Shape> <b>원천 연결 전</b>
          {"\u00a0· "}
          <span className={styles.gapSource}>{logs.source}</span>
        </p>
      )}
      {logs.state === "UNAVAILABLE" && <Unavailable what="오류 로그" source={logs.source} reason={logs.reason} compact />}
      {d?.analysis && (d.analysis.state !== "READY" ? (
        <p className={styles.quietLine}>
          <b>AI 로그 분석 실패</b>({d.analysis.reason})
        </p>
      ) : d.analysis.summary && (
        <p className={styles.logSummary}>
          <AiSign label="AI 요약" />
          {joinDots(d.analysis.summary)}
        </p>
      ))}
      {d && (shown.length === 0 ? (
        <p className={styles.quietLine}>{win !== null ? `최근 ${win}분 ` : ""}오류·경고 로그 없음</p>
      ) : (
        <ul className={styles.logList} aria-label="오류 로그 묶음">
          {shown.map((g) => (
            <LogRow key={g.id} g={g} polledAt={d.polledAt} />
          ))}
        </ul>
      ))}
      {rest > 0 && <p className={styles.quietLine}>외 {rest}개</p>}
      {d && d.failed.length > 0 && (
        <p className={styles.logNote}>
          <Shape tone="danger">■</Shape>{" "}
          {d.failed.map((f, i) => (
            <Fragment key={f.node}>
              {i > 0 && "\u00a0· "}
              <KeepNames text={`${f.node} 수집 실패(${f.reason})`} />
            </Fragment>
          ))}
        </p>
      )}
    </div>
  );
}
