"use client";

/**
 * 시나리오 재생 판의 네 칸(관리자 설계 4-10-6, 디자인 시스템 §12-15 시나리오 재생 판). 관제 상태, AI 제안, 정책 엔진 판정, 운영자
 * 조치와 Teams 카드다. 한 시각의 네 칸은 재생 세계의 같은 판에서 나온다.
 *
 * 움직임은 상태가 바뀌는 순간에만 둔다(§12-4). 경보가 서고 풀리면 관제 상태 칸의 테두리가 바뀌고 링 펄스가 서며, 새 경보 행은 4px
 * 아래에서 들어온다. AI 판단이 나면 정책 검사표의 행이 120ms 간격으로 차례로 켜지고, 자동 실행·되돌림 때 조치안 카드가 자리를
 * 옮긴다. Teams 통지가 나면 카드 미리보기가 아래에서 8px 들어온다. 재생 박자가 아닌 건너뛰기와 끌기, 움직임 줄이기에서는 모두
 * 바뀐 상태로 곧바로 서고 뜻은 형태 기호와 글이 싣는다.
 */

import { useRef, type CSSProperties, type ReactNode } from "react";
import Link from "next/link";
import { AI_FORBIDDEN, CHECK_VIEW_WORD, checkView, visibleChecks } from "@/lib/admin/aiPolicy";
import { certDaysLeft } from "@/lib/admin/certs";
import { INJECTED_ALERT } from "@/lib/admin/opsSim";
import { writeStateOf } from "@/lib/admin/opsHealth";
import { RUNBOOKS } from "@/lib/admin/runbooks";
import { priorityOf, teamsNoticeOf, type ReplayTimeline } from "@/lib/admin/scenarioWorld";
import { mmss } from "@/lib/admin/timeText";
import type { AiJudgmentTrace, AiProposal, ControlCenter, IncidentEvent, OpsAlert, ReadNodeState, ScenarioSpec } from "@/lib/admin/types";
import KeepNames from "../KeepNames";
import StatusShape, { type StatusShapeKind } from "../StatusShape";
import { StateChip } from "../ui";
import { useChangeFlags } from "../useChangeFlags";
import { useFlip } from "../useFlip";
import { useFreshKeys } from "../useFreshKeys";
import { AiMark } from "../control/parts";
import CheckMark from "../detail/CheckMark";
import styles from "./scenarios.module.css";

const SEV_SHAPE: Record<OpsAlert["severity"], StatusShapeKind> = { critical: "square", warning: "triangle", info: "circle" };
const SEV_TONE = { critical: "danger", warning: "warning", info: "info" } as const;
const NODE_WORD: Record<ReadNodeState, string> = { IN_SERVICE: "운영 중", PENDING: "기동 중", DRAINING: "드레이닝", OFF: "꺼짐" };

/** 재생의 가상 시각(mm:ss). 재생 시작부터 센다 */
const vt = (iso: string, t0: number) => mmss(Math.max(0, Date.parse(iso) - t0));

/** 칸 머리. 제목은 패널 제목 단계(§12-2)이고 오른쪽에 메타와 칩을 둔다 */
function Head({ title, lead, meta, id }: { title: ReactNode; lead?: ReactNode; meta?: ReactNode; id: string }) {
  return (
    <div className={styles.cellHead}>
      {lead}
      <h3 id={id} className={styles.cellTitle}>
        {title}
      </h3>
      {meta != null && <span className={styles.cellMeta}>{meta}</span>}
    </div>
  );
}

/* ===== 관제 상태 ===== */

export function StatePanel({ board, spec, animate }: { board: ControlCenter; spec: ScenarioSpec; animate: boolean }) {
  const sum = board.summary.state === "READY" ? board.summary.data : null;
  const alerts = board.alerts.state === "READY" ? board.alerts.data : [];
  const tier = board.readTier.state === "READY" ? board.readTier.data : null;
  const repl = board.replication.state === "READY" ? board.replication.data : null;
  const hosts = board.hosts.state === "READY" ? board.hosts.data : [];
  const trading = board.trading.state === "READY" ? board.trading.data : null;
  const ai = board.aiServer.state === "READY" ? board.aiServer.data : null;
  const writes = writeStateOf(board.trading);
  const crit = alerts.some((a) => a.severity === "critical");
  const tone = crit || writes !== null ? "danger" : alerts.some((a) => a.severity === "warning") ? "warning" : "success";
  const pulse = useChangeFlags({ tone }, 1800).tone && animate;
  const fresh = useFreshKeys(alerts.map((a) => a.id));
  const slo = sum?.slo;
  const off = (v: number | null, target: number | null, low: boolean) => v !== null && target !== null && (low ? v < target : v > target);
  const bot = hosts.find((h) => h.id === "bot-1");
  const db = hosts.find((h) => h.id === "db-primary");
  const app3 = tier?.nodes[0];
  const certs = board.jobs.state === "READY" ? board.jobs.data.certificates ?? [] : [];
  const www = certs.find((c) => c.domain === "www.sang.it.kr");
  const days = www ? certDaysLeft(www, Date.parse(board.generatedAt)) : null;
  const stateWord = tone === "danger" ? "장애" : tone === "warning" ? "주의" : "정상";
  return (
    <section className={styles.cell} data-tone={tone === "success" ? undefined : tone} data-pulse={pulse ? "" : undefined} aria-labelledby="rp-state">
      <Head id="rp-state" title="관제 상태" meta={<StateChip tone={tone} label={stateWord} />} />
      <div className={styles.slo} role="group" aria-label="서비스 수준">
        <Slo label="외부 가용성" value={sum?.availabilityPct?.toFixed(2) ?? null} unit="%" bad={off(sum?.availabilityPct ?? null, slo?.availabilityPct ?? null, true)} danger />
        <Slo label="응답 p95" value={sum?.p95Ms === null || sum === null ? null : String(sum.p95Ms)} unit="ms" bad={off(sum?.p95Ms ?? null, slo?.p95Ms ?? null, false)} />
        <Slo label="5xx" value={sum?.errorRatePct?.toFixed(2) ?? null} unit="%" bad={off(sum?.errorRatePct ?? null, slo?.errorRatePct ?? null, false)} />
      </div>
      <div className={styles.block}>
        <span className={styles.blockHead}>
          걸린 경보 <b className="num">{alerts.length}</b>
        </span>
        {alerts.length === 0 ? (
          <span className={styles.quiet}>걸린 경보 없음</span>
        ) : (
          <ul className={styles.alerts}>
            {alerts.slice(0, 3).map((a) => (
              <li key={a.id} className={styles.alert} data-fresh={animate && fresh.has(a.id) ? "" : undefined} data-tone={SEV_TONE[a.severity]}>
                <StatusShape kind={SEV_SHAPE[a.severity]} tone={SEV_TONE[a.severity]} className={styles.shape} />
                <span className={styles.alertName}>
                  <b>{a.name}</b>
                  <span>
                    <KeepNames text={a.target} />
                    {a.state === "ACKED" && " · 확인함"}
                  </span>
                </span>
              </li>
            ))}
            {alerts.length > 3 && <li className={styles.more}>외 {alerts.length - 3}건</li>}
          </ul>
        )}
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>앱 계층</dt>
          <dd>
            <span className="num">
              {tier ? `${tier.serving}대 · ${tier.avgCpuPct === null ? "평균 CPU 미수집" : `평균 CPU ${tier.avgCpuPct}%`}` : "원천 연결 전"}
            </span>
            {app3 && <span className={styles.sub}>app-3 {NODE_WORD[app3.state]}</span>}
          </dd>
        </div>
        <div>
          <dt>DB</dt>
          <dd>
            {repl ? (
              <>
                <span className={styles.nowrap}>
                  <KeepNames text={`Primary ${repl.primary}`} />
                </span>
                <span className={styles.sub} data-tone={repl.stream === "STREAMING" ? undefined : "danger"}>
                  {repl.stream === "STREAMING" ? `복제 스트리밍 · ${repl.lagSec === null ? "지연 미수집" : `${repl.lagSec.toFixed(1)}초`}` : "복제 없음"}
                </span>
              </>
            ) : (
              "원천 연결 전"
            )}
          </dd>
        </div>
        <div>
          <dt>쓰기</dt>
          <dd data-tone={writes ? "danger" : undefined}>
            {writes ? (
              <span className={styles.bad}>
                <StatusShape kind="square" tone="danger" className={styles.shape} />
                {writes.word}
              </span>
            ) : (
              <span>
                <StatusShape kind="circle" tone="success" className={styles.shape} /> 열림
              </span>
            )}
          </dd>
        </div>
        <div>
          <dt>시장 봇</dt>
          <dd>
            <span className="num">{trading ? `${trading.botOps} OPS` : "원천 연결 전"}</span>
            <span className={styles.sub}>
              {trading && trading.botOps === 0 ? "멈춤" : bot?.cpuPct == null ? "bot-1 CPU 미수집" : `bot-1 CPU ${bot.cpuPct}%`}
            </span>
          </dd>
        </div>
        <div>
          <dt>ai-1</dt>
          <dd>
            {ai?.link === "DOWN" ? (
              <span className={styles.bad}>
                <StatusShape kind="square" tone="danger" className={styles.shape} />
                응답 없음
              </span>
            ) : (
              <span>{ai ? (ai.busy ? "판단 요청 처리 중" : "대기") : "원천 연결 전"}</span>
            )}
            <span className={styles.sub}>가정</span>
          </dd>
        </div>
        {spec.id === "DISK_LOW" && db && (
          <div>
            <dt>디스크</dt>
            <dd>
              <span className="num">{db.diskUsedPct === null ? "db-primary 미수집" : `db-primary ${db.diskUsedPct}%`}</span>
              <span className={styles.sub}>{db.diskUsedPct === null ? "여유 미수집" : `여유 ${100 - db.diskUsedPct}%`}</span>
            </dd>
          </div>
        )}
        {spec.id === "CERT_EXPIRY" && www && (
          <div>
            <dt>인증서</dt>
            <dd>
              <span className="num">D-{days}</span>
              <span className={styles.sub}>
                <KeepNames text={`www.sang.it.kr · ${www.expiresOn}`} />
              </span>
            </dd>
          </div>
        )}
      </dl>
    </section>
  );
}

function Slo({ label, value, unit, bad, danger = false }: { label: string; value: string | null; unit: string; bad: boolean; danger?: boolean }) {
  const tone = bad ? (danger ? "danger" : "warning") : undefined;
  return (
    <span className={styles.sloCell} data-tone={tone}>
      <span className={styles.sloLabel}>{label}</span>
      <span className={`${styles.sloValue} num`}>
        {tone && <StatusShape kind={tone === "danger" ? "square" : "triangle"} tone={tone} className={styles.shape} />}
        {value ?? "미수집"}
        <small>{unit}</small>
      </span>
    </span>
  );
}

/* ===== AI 제안 ===== */

const STATUS_WORD: Partial<Record<AiProposal["status"], string>> = {
  PROPOSED: "대기",
  APPROVED: "승인 · 실행됨",
  AUTO_EXECUTED: "자동 실행됨",
  AUTO_REVERTED: "자동 되돌림",
  REVERTED: "되돌림",
  REJECTED: "거절",
  DEFERRED: "나중에",
  WITHDRAWN: "거둠",
  EXPIRED: "만료",
};

/** AI 문장의 첫 문장과 나머지. 첫 문장을 굵게 앞세운다. 문장 끝은 마침표 뒤의 빈칸으로 가른다 */
function firstSentence(text: string): [string, string] {
  const m = /\.\s/.exec(text);
  return m === null ? [text, ""] : [text.slice(0, m.index + 1), text.slice(m.index + 2)];
}

export function AiPanel({ board, t0, animate }: { board: ControlCenter; t0: number; animate: boolean }) {
  const ai = board.ai.state === "READY" ? board.ai.data : null;
  const server = board.aiServer.state === "READY" ? board.aiServer.data : null;
  const listRef = useRef<HTMLUListElement>(null);
  useFlip(listRef);
  const down = server?.link === "DOWN";
  const latest = ai?.history.find((h) => h.trace !== null) ?? null;
  const rejected = latest?.trace?.rejected ?? [];
  const shown = (ai?.proposals ?? []).slice(0, 3);
  const [lead, rest] = firstSentence(ai?.situation ?? "");
  return (
    <section className={styles.cell} aria-labelledby="rp-ai">
      <Head
        id="rp-ai"
        lead={
          <span className={styles.aiGlyph} aria-hidden="true">
            <AiMark size={16} />
          </span>
        }
        title="AI 제안"
        meta={
          <span className="num">
            {ai ? (ai.mode === "AUTO" ? "자동" : ai.mode === "STOPPED" ? "정지" : "수동") : "연결 전"}
            {ai?.judgedAt && !down ? ` · 판단 ${vt(ai.judgedAt, t0)}` : ""}
          </span>
        }
      />
      {down ? (
        <div className={styles.aiDown} role="status">
          <span className={`${styles.lead} ${styles.leadRow}`}>
            <StatusShape kind="square" tone="danger" className={styles.shape} />
            AI 서버 응답 없음 · 판단 없음
          </span>
          <p className={styles.prose}>자동 모드여도 조치가 없습니다. 경보, Teams 통지, ASG 지표 정책은 AI 없이 그대로 동작합니다(ADR 0035 전제 6, ADR 0038).</p>
        </div>
      ) : (
        <>
          <p className={styles.lead} key={lead}>
            <KeepNames text={lead} />
          </p>
          {rest && (
            <p className={styles.prose}>
              <KeepNames text={rest} />
            </p>
          )}
          {ai?.hypothesis && (
            <p className={styles.hypo}>
              <span className={styles.hypoKey}>가설</span>
              <span>
                <KeepNames text={ai.hypothesis} />
              </span>
            </p>
          )}
        </>
      )}
      {rejected.length > 0 && <Structured />}
      {!down && shown.length > 0 && (
        <ul className={styles.props} ref={listRef} aria-label="조치안">
          {shown.map((p) => (
            <li key={p.id} data-flip={p.id} className={styles.prop} data-status={p.status}>
              <span className={styles.propTitle}>
                <KeepNames text={p.title} />
              </span>
              <span className={styles.propMeta}>
                <StateChip
                  tone={p.status === "AUTO_REVERTED" || p.status === "REVERTED" ? "warning" : p.status === "PROPOSED" ? "neutral" : "off"}
                  shape={p.status === "PROPOSED" ? "ring" : p.status === "AUTO_REVERTED" || p.status === "REVERTED" ? "triangle" : "circle"}
                  label={STATUS_WORD[p.status] ?? p.status}
                />
                <span className="num">확신도 {p.confidencePct}%</span>
                {p.status === "PROPOSED" && ai?.mode === "AUTO" && (
                  <span className={styles.autoTag} data-ok={p.autoAllowed ? "" : undefined}>
                    {p.autoAllowed ? "자동 실행 가능" : "승인 필요"}
                  </span>
                )}
              </span>
              {p.autoHold && p.status === "PROPOSED" && <span className={styles.hold}>{p.autoHold}</span>}
            </li>
          ))}
        </ul>
      )}
      {!down && ai && ai.evidence.length > 0 && (
        <div className={styles.evidence} aria-label="판단 근거">
          {ai.evidence.slice(0, 4).map((e) => (
            <span key={e.id} className={styles.chip}>
              {e.severity && <StatusShape kind={SEV_SHAPE[e.severity]} tone={SEV_TONE[e.severity]} className={styles.shape} />}
              {e.label} <b className="num">{e.value}</b>
            </span>
          ))}
        </div>
      )}
      <p className={styles.foot}>모델 자리 · 시뮬레이터 규칙(모델 호출 없음)</p>
    </section>
  );
}

/** 근거 구조화(프롬프트 주입 재생). 사람이 읽는 경보와 모델이 받은 근거를 나란히 둔다 */
function Structured() {
  return (
    <div className={styles.split}>
      <div className={styles.splitBox}>
        <span className={styles.splitHead}>
          사람용 경보 <span className={styles.splitTag}>사람용 주석 · 모델에 싣지 않음</span>
        </span>
        <p className={styles.quote}>{INJECTED_ALERT.summary}</p>
        <code className={styles.labels}>
          {Object.entries(INJECTED_ALERT.labels)
            .map(([k, v]) => `${k}="${v}"`)
            .join(" ")}
        </code>
      </div>
      <div className={styles.splitBox}>
        <span className={styles.splitHead}>모델이 받은 근거</span>
        <dl className={styles.kv}>
          <dt>경보</dt>
          <dd>{INJECTED_ALERT.name}</dd>
          <dt>등급</dt>
          <dd>warning</dd>
          <dt>라벨</dt>
          <dd>
            <code>
              {Object.entries(INJECTED_ALERT.kept)
                .map(([k, v]) => `${k}=${v}`)
                .join(" ")}
            </code>
          </dd>
          <dt>버린 필드</dt>
          <dd>
            {INJECTED_ALERT.rejected.map((f) => (
              <code key={f} className={styles.dropped}>
                {f}
              </code>
            ))}
          </dd>
        </dl>
      </div>
    </div>
  );
}

/* ===== 정책 엔진 판정 ===== */

function CheckTable({ trace, stagger, caption }: { trace: Pick<AiJudgmentTrace, "candidates" | "checks"> & { mode?: AiJudgmentTrace["mode"] }; stagger: boolean; caption: string }) {
  // 모든 후보에 해당 없는 규칙은 거둔다. 좁은 칸에서 읽을 행만 남기고 거둔 규칙 수를 적는다. 모드가 없는 표(주입 문장을 따른 가정
  // 조치안)는 자동 실행 경로를 보이는 표라 자동 모드로 읽는다
  const { rows, hidden } = visibleChecks(trace.checks);
  const mode = trace.mode ?? "AUTO";
  return (
    <>
      <table className={styles.checks}>
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">규칙</th>
            {trace.candidates.map((c, i) => (
              <th key={c.id} scope="col" title={c.title}>
                후보 {i + 1}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.rule} data-in={stagger ? "" : undefined} style={stagger ? ({ "--row-i": i } as CSSProperties) : undefined}>
              <th scope="row">{r.label}</th>
              {r.results.map((cell, j) => {
                const view = checkView(r.rule, cell.state, mode);
                const word = CHECK_VIEW_WORD[view];
                return (
                  <td key={j} data-view={view}>
                    <span className={styles.checkCell}>
                      <CheckMark view={view} className={styles.shape} />
                      <span>
                        <span className="sr-only">{word} · </span>
                        {view === "autoOnly" ? `${word} · ${cell.note}` : cell.note || word}
                      </span>
                    </span>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {hidden.length > 0 && <p className={styles.foot}>해당 없는 규칙 {hidden.length}개 · {hidden.join(", ")}</p>}
    </>
  );
}

export function PolicyPanel({ board, spec, t0, hypothetical, animate }: {
  board: ControlCenter;
  spec: ScenarioSpec;
  t0: number;
  hypothetical: Pick<AiJudgmentTrace, "candidates" | "checks"> | null;
  animate: boolean;
}) {
  const ai = board.ai.state === "READY" ? board.ai.data : null;
  const down = board.aiServer.state === "READY" && board.aiServer.data.link === "DOWN";
  const latest = ai?.history.find((h) => h.trace !== null) ?? null;
  const trace = latest?.trace ?? null;
  // 새 판단이 나는 순간에만 행을 차례로 켠다. 처음 그린 판단과 건너뛴 판단은 곧바로 선다
  const fresh = useChangeFlags({ j: latest?.id ?? "" }, 1400).j === true && animate;
  const candidates = trace?.candidates.length ?? 0;
  return (
    <section className={styles.cell} aria-labelledby="rp-policy">
      <Head id="rp-policy" title="정책 엔진 판정" meta={latest && !down ? <span className="num">{vt(latest.at, t0)}부터 같은 판정</span> : undefined} />
      {down ? (
        <div className={styles.blockQuiet}>
          <span className={styles.leadSm}>판단 없음 · 정책 엔진에 들어온 조치 요청 없음</span>
          <p className={styles.prose}>ASG 지표 정책은 AI와 무관하게 앱 평균 CPU로 app-3을 켜고 끕니다. AWS에서는 지표 정책이 대수를 소유한다는 가정입니다.</p>
        </div>
      ) : trace === null ? (
        <div className={styles.blockQuiet}>
          <span className={styles.leadSm}>아직 판단이 없습니다</span>
          <p className={styles.prose}>AI는 5초마다 판단하고, 판단이 나면 후보마다 자동 실행 조건을 검사합니다.</p>
        </div>
      ) : candidates > 0 ? (
        <div className={styles.checkWrap} key={latest?.id} data-stagger={fresh ? "" : undefined}>
          <ol className={styles.cands}>
            {trace.candidates.map((c, i) => (
              <li key={c.id}>
                <b className="num">후보 {i + 1}</b> <KeepNames text={c.title} />
              </li>
            ))}
          </ol>
          <CheckTable trace={trace} stagger={fresh} caption="후보마다의 정책 엔진 검사" />
          <p className={styles.result}>
            <span className={styles.resultKey}>결과</span>
            <span>
              <KeepNames text={trace.result} />
            </span>
          </p>
        </div>
      ) : (
        <div className={styles.checkWrap} key={latest?.id} data-stagger={fresh ? "" : undefined}>
          <p className={styles.leadSm}>허용 목록에 맞는 조치 없음</p>
          {spec.forbidden.length > 0 && (
            <table className={styles.checks}>
              <caption className="sr-only">금지 목록 대조</caption>
              <thead>
                <tr>
                  <th scope="col">금지 목록</th>
                  <th scope="col">정책 엔진</th>
                </tr>
              </thead>
              <tbody>
                {spec.forbidden.map((n, i) => (
                  <tr key={n} data-in={fresh ? "" : undefined} style={fresh ? ({ "--row-i": i } as CSSProperties) : undefined}>
                    <th scope="row">
                      <span className="num">{n}</span> {AI_FORBIDDEN[n - 1]}
                    </th>
                    <td data-state="BLOCK">
                      <span className={styles.checkCell}>
                        <StatusShape kind="square" className={styles.shape} />
                        <span>조치 어휘에 없음 · 사람이 런북으로</span>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className={styles.result}>
            <span className={styles.resultKey}>결과</span>
            <span>
              <KeepNames text={trace.result} />
            </span>
          </p>
        </div>
      )}
      {hypothetical && !down && (
        <div className={styles.hypo2}>
          <span className={styles.blockHead}>주입 문장을 따른 조치안이 나왔다면</span>
          <ol className={styles.cands}>
            {hypothetical.candidates.map((c, i) => (
              <li key={c.id}>
                <b className="num">후보 {i + 1}</b> <KeepNames text={c.title} />
              </li>
            ))}
          </ol>
          <CheckTable trace={hypothetical} stagger={false} caption="주입 문장을 따른 가정 조치안의 정책 엔진 검사" />
          <p className={styles.foot}>모델이 낸 조치가 아니라 가정입니다. 판단 과정과 같은 규칙 함수로 만든 검사표입니다</p>
        </div>
      )}
    </section>
  );
}

/* ===== 운영자 조치와 Teams 카드 ===== */

const OPERATOR_KINDS = new Set<IncidentEvent["kind"]>(["ACKED", "APPROVED", "REJECTED", "DEFERRED", "REVERTED", "ACTED", "SILENCED"]);

export function OperatorPanel({ spec, timeline, sec, t0, animate }: { spec: ScenarioSpec; timeline: ReplayTimeline; sec: number; t0: number; animate: boolean }) {
  const done = timeline.events.filter((x) => x.sec <= sec && x.e.actor === "OPERATOR" && OPERATOR_KINDS.has(x.e.kind));
  const shown = done.slice(-4);
  const next = spec.cues.find((c) => c.atSec > sec && (c.mode === "ANY" || c.mode === timeline.mode) && c.kind !== "WORLD") ?? null;
  const notices = timeline.events.filter((x) => x.sec <= sec && x.e.kind === "NOTIFIED");
  const last = notices[notices.length - 1] ?? null;
  const fresh = useChangeFlags({ n: last?.e.id ?? "" }, 700).n === true && animate;
  const rb = spec.runbookId ? RUNBOOKS.find((r) => r.id === spec.runbookId) ?? null : null;
  return (
    <section className={styles.cell} aria-labelledby="rp-op">
      <Head id="rp-op" title="운영자 조치" meta={<span className="num">{done.length}건</span>} />
      {shown.length === 0 ? (
        <span className={styles.quiet}>아직 사람이 한 일이 없습니다</span>
      ) : (
        <ol className={styles.ops}>
          {done.length > shown.length && <li className={styles.more}>앞 {done.length - shown.length}건</li>}
          {shown.map(({ sec: s, e }) => (
            <li key={e.id} className={styles.op}>
              <span className={`${styles.opTime} num`}>{mmss(s * 1000)}</span>
              <span className={styles.opText}>
                <KeepNames text={e.text} />
              </span>
            </li>
          ))}
        </ol>
      )}
      {next && (
        <p className={styles.next}>
          <span className={styles.nextKey}>다음 대본</span>
          <span className="num">{mmss(next.atSec * 1000)}</span>
          <span>
            <KeepNames text={next.note} />
          </span>
        </p>
      )}
      <div className={styles.links}>
        {rb ? (
          <Link href={`/admin/runbooks?id=${rb.id}`} className={styles.textLink}>
            {rb.title} 런북 · {rb.steps.length}단계
          </Link>
        ) : (
          <span className={styles.quiet}>관련 런북 없음</span>
        )}
      </div>
      <div className={styles.teams}>
        <span className={styles.blockHead}>
          Teams 카드 <span className="num">{notices.length}건</span>
        </span>
        {last === null ? (
          <span className={styles.quiet}>아직 보낸 통지가 없습니다</span>
        ) : (
          <TeamsMini text={last.e.text} at={last.sec} fresh={fresh} key={last.e.id} />
        )}
        <p className={styles.foot}>재생이라 밖으로 보내지 않습니다. 사람 몫은 재생 대본이고 콘솔이 실행한 일이 아닙니다</p>
      </div>
    </section>
  );
}

const PRIORITY_SHAPE: Record<string, { shape: StatusShapeKind; tone: "danger" | "warning" | "success" }> = {
  P1: { shape: "square", tone: "danger" },
  P2: { shape: "triangle", tone: "warning" },
  P3: { shape: "triangle", tone: "warning" },
  P4: { shape: "circle", tone: "success" },
};

/** 보고 서비스 카드 모양의 작은 미리보기(§12-11 카드의 작은 꼴). Teams의 모양을 흉내 내지 않는다 */
function TeamsMini({ text, at, fresh }: { text: string; at: number; fresh: boolean }) {
  const n = teamsNoticeOf(text);
  const pri = n.priority ?? priorityOf(text);
  const look = pri ? PRIORITY_SHAPE[pri] : { shape: "circle" as StatusShapeKind, tone: "warning" as const };
  return (
    <article className={styles.card} data-fresh={fresh ? "" : undefined} data-tone={look.tone}>
      <span className={styles.cardEyebrow}>페일스톱 운영 관제 · exchange / AWS</span>
      <span className={styles.cardTitle}>
        <StatusShape kind={look.shape} tone={look.tone} className={styles.shape} />
        <span>
          <KeepNames text={n.title} />
        </span>
      </span>
      <span className={styles.cardGrade}>{pri ? `${pri} · ${n.label}` : n.label}</span>
      <span className={`${styles.cardMeta} num`}>
        {n.target ? `대상 ${n.target} · ` : ""}재생 {mmss(at * 1000)}
      </span>
    </article>
  );
}
