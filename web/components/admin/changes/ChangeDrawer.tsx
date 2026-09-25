"use client";

/**
 * 변경 요청 드로어(오른쪽 560px, 관리자 설계 4-10-4). 사건 드로어와 같은 층이다. 변경 요청 등록, 계획 작업 창만 등록, 요청 상세
 * (단계 막대, 승인 요청과 승인 목록, 진행, 종료, 취소), 창 상세(일찍 닫기)를 한 자리에서 한다. 승인은 기존 승인 목록에서 1인 승인
 * 티켓(ops.change.approve)을 발급·실행한다. 진행은 같은 대상의 계획 작업 창을 연다. 모든 조작은 감사와 사건 밖 운영 기록에 남는다.
 *
 * http는 창의 등록·조기 종료만 서버로 보내고, 변경 요청은 서버 경로가 3라운드 계약 초안이라 원천 연결 전이다.
 */

import Link from "next/link";
import { useMemo, useState, type ReactNode } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { classify } from "@/lib/admin/actionLog";
import {
  actionsIn,
  CHANGE_STATE_WORD,
  changeInputError,
  changeSpan,
  changeStages,
  defaultSpan,
  kstFields,
  kstIso,
  nextStep,
  planSpanWord,
  RISK_WORD,
  WINDOW_RULES,
  windowInputError,
  windowOpen,
  windowPhase,
  windowRemainWord,
  windowTargetWord,
} from "@/lib/admin/changes";
import { useCan } from "@/lib/admin/store";
import { mdhm } from "@/lib/admin/timeText";
import type { ActionRecord, ChangeInput, ChangeRequest, ChangeRisk, ControlCenter, MaintenanceWindow, WindowLabelKey } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import ApprovalList from "../ApprovalList";
import ConfirmDialog from "../ConfirmDialog";
import KeepNames from "../KeepNames";
import RequestButton from "../RequestButton";
import StageBar from "../StageBar";
import StatusShape from "../StatusShape";
import { Drawer } from "../layer/Layer";
import { Segmented, SimMark, StateChip } from "../ui";
import { useClock } from "../control/parts";
import adminStyles from "../admin.module.css";
import detailStyles from "../detail/detail.module.css";
import styles from "./changes.module.css";

export type ChangeLayer = { kind: "new" } | { kind: "window" } | { kind: "change"; id: string } | { kind: "windowDetail"; id: string };

const RISKS: { value: ChangeRisk; label: string }[] = [
  { value: "LOW", label: "낮음" },
  { value: "MEDIUM", label: "중간" },
  { value: "HIGH", label: "높음" },
];

const NODE_CHOICES = ["app-1", "app-2", "app-3", "db-primary", "db-standby", "bot-1"];
const ROLE_CHOICES = ["app", "db", "bot"];

export const CHANGE_TONE: Record<ChangeRequest["state"], { tone: "success" | "warning" | "neutral" | "off"; shape: "dashed" | "ring" | "play" | "circle" | "triangle" }> = {
  REQUESTED: { tone: "off", shape: "dashed" },
  APPROVED: { tone: "neutral", shape: "ring" },
  IN_PROGRESS: { tone: "neutral", shape: "play" },
  DONE: { tone: "success", shape: "circle" },
  ROLLED_BACK: { tone: "warning", shape: "triangle" },
  CANCELED: { tone: "off", shape: "ring" },
};

export function ChangeChip({ state }: { state: ChangeRequest["state"] }) {
  const t = CHANGE_TONE[state];
  return <StateChip tone={t.tone} shape={t.shape} label={CHANGE_STATE_WORD[state]} />;
}

/* ===== 입력 ===== */

/** 입력 한 칸. 입력 요소가 하나면 이름표가 그 요소를 가리키고, 분절 조작처럼 묶음이면 이름표는 글이고 묶음이 제 이름을 싣는다 */
function Field({ label, htmlFor, children, hint }: { label: string; htmlFor?: string; children: ReactNode; hint?: string }) {
  const text = (
    <>
      {label}
      {hint && <span className={styles.fieldHint}>{hint}</span>}
    </>
  );
  return (
    <div className={styles.field}>
      {htmlFor ? (
        <label className={styles.fieldLabel} htmlFor={htmlFor}>
          {text}
        </label>
      ) : (
        <span className={styles.fieldLabel} aria-hidden="true">
          {text}
        </span>
      )}
      {children}
    </div>
  );
}

/** 날짜와 시각 한 쌍(KST) */
function When({ id, label, value, onChange }: { id: string; label: string; value: { date: string; time: string }; onChange: (v: { date: string; time: string }) => void }) {
  return (
    <Field label={label} htmlFor={`${id}-date`} hint="KST">
      <span className={styles.when}>
        <input id={`${id}-date`} type="date" className={styles.input} value={value.date} onChange={(e) => onChange({ ...value, date: e.target.value })} />
        <input id={`${id}-time`} type="time" aria-label={`${label} 시각`} className={styles.input} value={value.time} step={300} onChange={(e) => onChange({ ...value, time: e.target.value })} />
      </span>
    </Field>
  );
}

/** 창 대상. 경보명과 라벨 넷이고 둘 가운데 하나는 있어야 한다 */
function TargetFields({ alertname, labels, onAlert, onLabel, known }: {
  alertname: string;
  labels: Partial<Record<WindowLabelKey, string>>;
  onAlert: (v: string) => void;
  onLabel: (k: WindowLabelKey, v: string) => void;
  known: string[];
}) {
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.fieldLabel}>
        창 대상
        <span className={styles.fieldHint}>경보명과 라벨이 모두 맞는 경보가 대상입니다</span>
      </legend>
      <Field label="경보명" htmlFor="win-alert" hint="선택">
        <input id="win-alert" className={styles.input} list="win-alert-names" value={alertname} placeholder="예: 앱 계층 CPU 높음" onChange={(e) => onAlert(e.target.value)} />
        <datalist id="win-alert-names">
          {known.map((n) => (
            <option key={n} value={n} />
          ))}
        </datalist>
      </Field>
      <div className={styles.labelGrid}>
        <Field label="node" htmlFor="win-node">
          <select id="win-node" className={styles.select} value={labels.node ?? ""} onChange={(e) => onLabel("node", e.target.value)}>
            <option value="">없음</option>
            {NODE_CHOICES.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </Field>
        <Field label="role" htmlFor="win-role">
          <select id="win-role" className={styles.select} value={labels.role ?? ""} onChange={(e) => onLabel("role", e.target.value)}>
            <option value="">없음</option>
            {ROLE_CHOICES.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </Field>
        <Field label="component" htmlFor="win-component">
          <input id="win-component" className={styles.input} value={labels.component ?? ""} placeholder="예: bot" onChange={(e) => onLabel("component", e.target.value)} />
        </Field>
        <Field label="mountpoint" htmlFor="win-mount">
          <input id="win-mount" className={styles.input} value={labels.mountpoint ?? ""} placeholder="예: /" onChange={(e) => onLabel("mountpoint", e.target.value)} />
        </Field>
      </div>
    </fieldset>
  );
}

function knownAlertNames(center: ControlCenter): string[] {
  const live = center.alerts.state === "READY" ? center.alerts.data.map((a) => a.name) : [];
  const base = ["앱 계층 CPU 높음", "응답 지연 증가", "호스트 CPU 높음", "메모리 부족", "봇 용량 보호", "CPU 크레딧 잔량 낮음"];
  return [...new Set([...live, ...base])];
}

function ChangeForm({ center, onDone }: { center: ControlCenter; onDone: (id: string) => Promise<void> }) {
  const canWrite = useCan("BOT_OPS");
  const init = useMemo(() => defaultSpan(Date.now()), []);
  const [title, setTitle] = useState("");
  const [target, setTarget] = useState("");
  const [risk, setRisk] = useState<ChangeRisk>("LOW");
  const [start, setStart] = useState(kstFields(init.start));
  const [end, setEnd] = useState(kstFields(init.end));
  const [plan, setPlan] = useState("");
  const [rollback, setRollback] = useState("");
  const [alertname, setAlertname] = useState("");
  const [labels, setLabels] = useState<Partial<Record<WindowLabelKey, string>>>({});
  const [busy, setBusy] = useState(false);
  const startsAt = kstIso(start.date, start.time);
  const endsAt = kstIso(end.date, end.time);
  const input: ChangeInput | null = startsAt && endsAt ? { title, target, plan, rollback, risk, startsAt, endsAt, alertname: alertname || null, labels } : null;
  const error = input === null ? "시작과 끝 시각을 읽지 못했습니다" : changeInputError(input, Date.now());
  const submit = async () => {
    if (!input || error) return;
    setBusy(true);
    try {
      const c = await getAdminApi().createChange(input);
      toast(`${c.id} 변경 요청을 등록했습니다. 총괄의 승인이 필요합니다`, "info");
      await onDone(c.id);
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "등록 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className={styles.form}
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <Field label="제목" htmlFor="chg-title">
        <input id="chg-title" className={styles.input} value={title} maxLength={80} placeholder="예: app-1 보안 업데이트 적용" onChange={(e) => setTitle(e.target.value)} />
      </Field>
      <div className={styles.twoCol}>
        <Field label="대상" htmlFor="chg-target">
          <input id="chg-target" className={styles.input} value={target} maxLength={80} placeholder="예: app-1 · dnf" onChange={(e) => setTarget(e.target.value)} />
        </Field>
        <Field label="위험">
          <span className={styles.segWrap}>
            <Segmented items={RISKS} value={risk} onChange={setRisk} ariaLabel="위험" semantics="pressed" size="sm" />
          </span>
        </Field>
      </div>
      <When id="chg-start" label="시작" value={start} onChange={setStart} />
      <When id="chg-end" label="끝" value={end} onChange={setEnd} />
      <Field label="작업 계획" htmlFor="chg-plan">
        <textarea id="chg-plan" className={styles.textarea} rows={3} maxLength={500} value={plan} placeholder="무엇을 어떤 순서로 바꾸고 무엇으로 확인하는지" onChange={(e) => setPlan(e.target.value)} />
      </Field>
      <Field label="되돌리기 계획" htmlFor="chg-rollback">
        <textarea id="chg-rollback" className={styles.textarea} rows={2} maxLength={500} value={rollback} placeholder="실패하면 무엇으로 되돌리는지" onChange={(e) => setRollback(e.target.value)} />
      </Field>
      <TargetFields
        alertname={alertname}
        labels={labels}
        known={knownAlertNames(center)}
        onAlert={setAlertname}
        onLabel={(k, v) => setLabels((l) => ({ ...l, [k]: v }))}
      />
      <div className={styles.formFoot}>
        <span className={styles.formNote} role="status">
          {!canWrite ? "봇 운영(BOT_OPS) 이상이 등록합니다" : error ?? "등록하면 승인 대기로 서고 총괄이 승인 티켓으로 승인합니다"}
        </span>
        <button type="submit" className={adminStyles.neutralBtn} disabled={!canWrite || busy || error !== null}>
          {busy ? "등록 중" : "변경 요청 등록"}
        </button>
      </div>
    </form>
  );
}

function WindowForm({ center, onDone }: { center: ControlCenter; onDone: (id: string) => Promise<void> }) {
  const canWrite = useCan("BOT_OPS");
  const init = useMemo(() => defaultSpan(Date.now()), []);
  const [now, setNow] = useState(true);
  const [start, setStart] = useState(kstFields(init.start));
  const [end, setEnd] = useState(kstFields(init.start));
  const [alertname, setAlertname] = useState("");
  const [labels, setLabels] = useState<Partial<Record<WindowLabelKey, string>>>({});
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const startsAt = now ? null : kstIso(start.date, start.time);
  const endsAt = kstIso(end.date, end.time);
  const error = (!now && startsAt === null) || endsAt === null
    ? "시작과 끝 시각을 읽지 못했습니다"
    : windowInputError({ startsAt, endsAt, alertname: alertname || null, labels, reason }, Date.now());
  const submit = async () => {
    if (error || endsAt === null) return;
    setBusy(true);
    try {
      const w = await getAdminApi().createWindow({ startsAt, endsAt, alertname: alertname || null, labels, reason });
      toast(`계획 작업 창 ${w.id}을 등록했습니다`, "info");
      await onDone(w.id);
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "등록 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className={styles.form}
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <p className={styles.formLead}>변경 요청 없이 창만 등록합니다. 창 안에서만 AI가 주의 경보를 30분까지 보류할 수 있고, 사람의 보류는 창과 무관합니다.</p>
      <Field label="시작">
        <span className={styles.segWrap}>
          <Segmented
            items={[
              { value: "now", label: "지금" },
              { value: "later", label: "예약" },
            ]}
            value={now ? "now" : "later"}
            onChange={(v) => setNow(v === "now")}
            ariaLabel="시작"
            semantics="pressed"
            size="sm"
          />
        </span>
      </Field>
      {!now && <When id="win-start" label="시작 시각" value={start} onChange={setStart} />}
      <When id="win-end" label="끝" value={end} onChange={setEnd} />
      <TargetFields alertname={alertname} labels={labels} known={knownAlertNames(center)} onAlert={setAlertname} onLabel={(k, v) => setLabels((l) => ({ ...l, [k]: v }))} />
      <Field label="사유" htmlFor="win-reason" hint="200자 이내">
        <input id="win-reason" className={styles.input} value={reason} maxLength={200} placeholder="예: 봇 설정 교체 작업" onChange={(e) => setReason(e.target.value)} />
      </Field>
      <div className={styles.formFoot}>
        <span className={styles.formNote} role="status">
          {!canWrite ? "봇 운영(BOT_OPS) 이상이 등록합니다" : error ?? "끝은 시작 뒤 24시간 이내입니다"}
        </span>
        <button type="submit" className={adminStyles.neutralBtn} disabled={!canWrite || busy || error !== null}>
          {busy ? "등록 중" : "창 등록"}
        </button>
      </div>
    </form>
  );
}

/* ===== 상세 ===== */

/** 창 한 줄. 열림·예약·닫힘과 남은 시간이다. 매초 바뀌는 글자라 이 조각만 시계를 탄다 */
export function WindowState({ w }: { w: MaintenanceWindow }) {
  const now = useClock();
  const phase = windowPhase(w, now);
  if (phase === "OPEN") {
    return (
      <span className={styles.windowState} data-phase="OPEN">
        <StatusShape kind="play" size={9} className={styles.playShape} />
        열림 · <b className="num">{windowRemainWord(Date.parse(w.endsAt) - now)}</b> 남음
      </span>
    );
  }
  if (phase === "SCHEDULED") {
    return (
      <span className={styles.windowState}>
        <StatusShape kind="ring" size={9} /> 예약 · <span className="num">{mdhm(w.startsAt)}</span> 시작
      </span>
    );
  }
  return (
    <span className={styles.windowState}>
      <StatusShape kind="circle" size={9} className={styles.doneShape} />
      닫힘 · <span className="num">{mdhm(w.endedAt ?? w.endsAt)}</span>
      {w.endedAt ? ` · ${w.endedBy ?? ""} 조기 종료` : " · 끝 시각"}
    </span>
  );
}

/**
 * 창의 시각 안에 일어난 조치(조치 기록에서 거른 것). 임계값·AI 한도를 바꾼 조치는 되돌리기를 그 화면의 승인 절차로 보낸다.
 * 이미 되돌렸거나 거부된 조치에는 보내지 않는다
 */
export function ActionRows({ rows }: { rows: ActionRecord[] }) {
  if (rows.length === 0) return <div className={detailStyles.empty}>이 창의 시각 안에 조치 기록 없음</div>;
  return (
    <ol className={styles.inWindow}>
      {rows.map((r) => {
        const c = classify(r.action);
        const viaScreen = r.result !== "OK" ? null : r.category === "THRESHOLD" ? "/admin/thresholds" : r.category === "AI_LIMIT" ? "/admin/ai#ai-limits" : null;
        return (
          <li key={r.id} className={styles.inWindowRow}>
            <span className={`${styles.inWindowTime} num`}>{mdhm(r.at)}</span>
            <span className={styles.inWindowText}>
              <b>{r.actorName}</b> {c.gloss} · <KeepNames text={r.title ?? r.target} />
              {r.result === "REVERTED" && <span className={styles.reverted}> · 되돌림</span>}
              {r.result === "DENIED" && <span className={styles.denied}> · 거부</span>}
            </span>
            {viaScreen && (
              <Link href={viaScreen} className={styles.inlineLink}>
                되돌리기
              </Link>
            )}
          </li>
        );
      })}
    </ol>
  );
}

function ChangeDetail({ c, windows, records, sim, onChanged }: {
  c: ChangeRequest;
  windows: MaintenanceWindow[];
  records: ActionRecord[] | null;
  sim: boolean;
  onChanged: () => void;
}) {
  const canWrite = useCan("BOT_OPS");
  const w = c.windowId === null ? null : windows.find((x) => x.id === c.windowId) ?? null;
  const span = changeSpan(c, windows);
  const inWindow = records && c.startedAt ? actionsIn(records, span) : null;
  const [ask, setAsk] = useState<"start" | "finish" | "cancel" | null>(null);
  const [outcome, setOutcome] = useState<"DONE" | "ROLLED_BACK">("DONE");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const act = async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, "info");
      setAsk(null);
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "처리 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  const api = getAdminApi();
  const stepText = nextStep(c);
  return (
    <>
      <section className={detailStyles.section} aria-label="변경 요청 단계">
        <StageBar items={changeStages(c, inWindow?.length ?? null)} label="변경 요청 단계" />
        {stepText && (
          <p className={styles.next}>
            <span className={styles.nextKey}>다음</span> {stepText}
          </p>
        )}
      </section>
      <section className={detailStyles.section} aria-label="변경 요청 내용">
        <dl className={styles.kv}>
          <dt>대상</dt>
          <dd>
            <KeepNames text={c.target} />
          </dd>
          <dt>계획 시각</dt>
          <dd className="num">{planSpanWord(c.startsAt, c.endsAt)}</dd>
          <dt>위험</dt>
          <dd>
            <span className={styles.risk} data-risk={c.risk}>
              {c.risk === "LOW" ? <StatusShape kind="circle" size={9} /> : <StatusShape kind="triangle" size={9} />}
              {RISK_WORD[c.risk]}
            </span>
          </dd>
          <dt>창 대상</dt>
          <dd>
            <code className={styles.code}>{windowTargetWord(c)}</code>
          </dd>
          <dt>작업 계획</dt>
          <dd>{c.plan}</dd>
          <dt>되돌리기</dt>
          <dd>{c.rollback}</dd>
        </dl>
      </section>
      <section className={detailStyles.section} aria-label="변경 요청 기록">
        <div className={detailStyles.sectionHead}>
          <h3 className={detailStyles.sectionTitle}>기록</h3>
        </div>
        <dl className={styles.kv}>
          <dt>등록</dt>
          <dd>
            {c.requestedBy} · <span className="num">{mdhm(c.requestedAt)}</span>
          </dd>
          <dt>승인</dt>
          <dd>
            {c.approvedAt ? (
              <>
                {c.approvedBy} · <span className="num">{mdhm(c.approvedAt)}</span>
                {c.approvalId && (
                  <>
                    {" "}
                    · 티켓 <code className={styles.code}>{c.approvalId}</code>
                  </>
                )}
              </>
            ) : c.state === "CANCELED" ? (
              "없음"
            ) : (
              "승인 대기 · ops.change.approve 1인 승인"
            )}
          </dd>
          <dt>계획 작업 창</dt>
          <dd>
            {w ? (
              <span className={styles.windowLine}>
                <code className={styles.code}>{w.id}</code>
                <WindowState w={w} />
              </span>
            ) : c.state === "APPROVED" ? (
              "진행하면 열립니다"
            ) : c.state === "REQUESTED" ? (
              "승인 뒤 진행하면 열립니다"
            ) : (
              "없음"
            )}
          </dd>
          <dt>종료</dt>
          <dd>
            {c.closedAt ? (
              <>
                {c.closedBy ?? "기록 없음"} · <span className="num">{mdhm(c.closedAt)}</span>
              </>
            ) : (
              "종료 전"
            )}
          </dd>
          <dt>결과</dt>
          <dd>{c.result ?? "종료 전"}</dd>
        </dl>
      </section>
      {c.state === "REQUESTED" && (
        <section className={detailStyles.section} aria-label="승인">
          <div className={detailStyles.sectionHead}>
            <h3 className={detailStyles.sectionTitle}>승인 · 1인 승인 티켓</h3>
            <span className={detailStyles.sectionMeta}>총괄(SUPER)이 발급·실행합니다</span>
          </div>
          <div className={styles.approveRow}>
            <RequestButton
              action="ops.change.approve"
              payload={{ action: "ops.change.approve", id: c.id, title: `${c.id} · ${c.title}` }}
              label="승인 요청"
              confirm={{
                title: "변경 요청 승인을 요청합니다",
                description: "승인 목록에 요청이 서고, 발급한 티켓을 실행하면 이 요청이 예정으로 바뀝니다. 승인은 작업 합의의 기록이고 창을 여는 권한을 줄이지 않습니다.",
                target: `${c.id} · ${c.title}`,
                tone: "warning",
                confirmLabel: "승인 요청",
              }}
            />
          </div>
          <ApprovalList actions={["ops.change.approve"]} onChange={onChanged} emptyLabel="발급 대기 승인 없음" emptyDesc="승인 요청을 누르면 여기에 발급 대기로 섭니다" />
        </section>
      )}
      {c.startedAt && (
        <section className={detailStyles.section} aria-label="창 안의 조치">
          <div className={detailStyles.sectionHead}>
            <h3 className={detailStyles.sectionTitle}>창 안의 조치</h3>
            <span className={detailStyles.sectionMeta}>
              {records === null ? "조치 기록을 읽는 중" : `조치 기록에서 거름${sim ? "" : " · 받아 온 쪽만"}`}
            </span>
          </div>
          {inWindow === null ? <div className={detailStyles.empty}>조치 기록을 읽는 중입니다</div> : <ActionRows rows={inWindow} />}
          <p className={detailStyles.note}>임계값·AI 한도의 되돌리기는 그 화면의 승인 절차로 합니다. 이 화면에서 따로 되돌리지 않습니다.</p>
        </section>
      )}
      <div className={styles.actions}>
        {(c.state === "REQUESTED" || c.state === "APPROVED") && (
          <button type="button" className={adminStyles.neutralBtn} disabled={!canWrite || busy} onClick={() => setAsk("cancel")}>
            요청 취소
          </button>
        )}
        {c.state === "APPROVED" && (
          <button type="button" className={adminStyles.neutralBtn} disabled={!canWrite || busy} onClick={() => setAsk("start")}>
            진행
          </button>
        )}
        {c.state === "IN_PROGRESS" && (
          <button type="button" className={adminStyles.neutralBtn} disabled={!canWrite || busy} onClick={() => setAsk("finish")}>
            종료
          </button>
        )}
        {!canWrite && c.state !== "DONE" && c.state !== "ROLLED_BACK" && c.state !== "CANCELED" && <span className={styles.formNote}>봇 운영(BOT_OPS) 이상이 합니다</span>}
      </div>
      {ask === "start" && (
        <ConfirmDialog
          title="변경 작업을 시작합니다"
          description={`같은 대상의 계획 작업 창을 지금부터 ${mdhm(c.endsAt)}까지 엽니다. 창 안에서는 AI가 주의 경보를 30분까지 보류할 수 있습니다.`}
          target={windowTargetWord(c)}
          blastRadius="감사 로그(msp.change.start, msp.window.create)와 사건 밖 운영 기록에 남습니다"
          tone="warning"
          confirmLabel="진행"
          busy={busy}
          onConfirm={() => void act(() => api.startChange(c.id), `${c.id} 진행 · 계획 작업 창을 열었습니다`)}
          onClose={() => setAsk(null)}
        />
      )}
      {ask === "finish" && (
        <ConfirmDialog
          title="변경 작업을 종료합니다"
          description="결과를 적고 이 요청의 계획 작업 창을 닫습니다. 되돌림은 되돌리기 계획을 실행했다는 뜻입니다."
          target={`${c.id} · ${c.title}`}
          blastRadius="감사 로그(msp.change.finish)와 변경 기록에 남습니다"
          tone="warning"
          confirmLabel="종료"
          busy={busy}
          onConfirm={() => void act(() => api.finishChange(c.id, outcome, note), `${c.id} 종료 · ${outcome === "DONE" ? "완료" : "되돌림"}`)}
          onClose={() => setAsk(null)}
        >
          <div className={styles.finishForm}>
            <span className={styles.fieldLabel} id="finish-outcome">
              결과
            </span>
            <Segmented
              items={[
                { value: "DONE", label: "완료" },
                { value: "ROLLED_BACK", label: "되돌림" },
              ]}
              value={outcome}
              onChange={setOutcome}
              ariaLabel="결과"
              semantics="pressed"
              size="sm"
            />
            <label className={styles.fieldLabel} htmlFor="finish-note">
              결과 메모
            </label>
            <textarea id="finish-note" className={styles.textarea} rows={2} maxLength={500} value={note} placeholder="무엇을 확인했는지, 되돌렸다면 왜인지" onChange={(e) => setNote(e.target.value)} />
          </div>
        </ConfirmDialog>
      )}
      {ask === "cancel" && (
        <ConfirmDialog
          title="변경 요청을 취소합니다"
          description="취소한 요청은 다시 진행하지 않습니다. 같은 작업이 필요하면 새로 등록합니다."
          target={`${c.id} · ${c.title}`}
          blastRadius="감사 로그(msp.change.cancel)에 남습니다"
          tone="danger"
          confirmLabel="요청 취소"
          busy={busy}
          onConfirm={() => void act(() => api.cancelChange(c.id), `${c.id} 취소`)}
          onClose={() => setAsk(null)}
        />
      )}
    </>
  );
}

/** 창 상세. 창만 따로 등록한 창이거나 http의 창이다. 열린 창은 누구나 일찍 닫는다 */
export function EndWindowButton({ w, onChanged, label = "창 일찍 닫기" }: { w: MaintenanceWindow; onChanged: () => void; label?: string }) {
  const [ask, setAsk] = useState(false);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try {
      await getAdminApi().endWindow(w.id);
      toast(`계획 작업 창 ${w.id}을 닫았습니다`, "info");
      setAsk(false);
      onChanged();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "창 닫기 실패", "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <button type="button" className={adminStyles.neutralBtn} disabled={busy} onClick={() => setAsk(true)}>
        {label}
      </button>
      {ask && (
        <ConfirmDialog
          title="계획 작업 창을 일찍 닫습니다"
          description="창이 닫히면 AI는 이 창 안의 경보를 보류하지 못합니다. 이미 건 보류와 사람의 보류는 그대로입니다. AI 권한을 줄이는 조작이라 누구나 할 수 있습니다."
          target={`${w.id} · ${windowTargetWord(w)}`}
          blastRadius="감사 로그(msp.window.end)와 사건 밖 운영 기록에 남습니다"
          tone="warning"
          confirmLabel="창 닫기"
          busy={busy}
          onConfirm={() => void run()}
          onClose={() => setAsk(false)}
        />
      )}
    </>
  );
}

function WindowDetail({ w, onChanged }: { w: MaintenanceWindow; onChanged: () => void }) {
  // 창 상태 글(WindowState)과 같은 1초 박자로 가른다. 느린 박자면 방금 연 창에 '일찍 닫기'가 늦게 선다
  const now = useClock();
  return (
    <>
      <section className={detailStyles.section} aria-label="계획 작업 창">
        <dl className={styles.kv}>
          <dt>상태</dt>
          <dd>
            <WindowState w={w} />
          </dd>
          <dt>시각</dt>
          <dd className="num">{planSpanWord(w.startsAt, w.endsAt)}</dd>
          <dt>대상</dt>
          <dd>
            <code className={styles.code}>{windowTargetWord(w)}</code>
          </dd>
          <dt>사유</dt>
          <dd>{w.reason}</dd>
          <dt>등록</dt>
          <dd>{w.createdBy}</dd>
        </dl>
      </section>
      <section className={detailStyles.section} aria-label="창의 규칙">
        <ul className={styles.rules}>
          {WINDOW_RULES.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      </section>
      <div className={styles.actions}>{windowOpen(w, now) && <EndWindowButton w={w} onChanged={onChanged} />}</div>
    </>
  );
}

export default function ChangeDrawer({ layer, center, records, sim, onClose, onChanged, onOpen }: {
  layer: ChangeLayer;
  center: ControlCenter;
  records: ActionRecord[] | null;
  sim: boolean;
  onClose: () => void;
  onChanged: () => Promise<void>;
  onOpen: (layer: ChangeLayer) => void;
}) {
  const desk = center.changes.state === "READY" ? center.changes.data : null;
  const changes = desk?.changes.state === "READY" ? desk.changes.data : null;
  const windows = desk?.windows ?? [];
  // 머리 보조 줄은 줄바꿈 상자(flex)라 글 조각을 span으로 둔다. 맨 글이 섞이면 표식의 보조기술 글과 한 줄 상자를 이뤄 감김으로 잡힌다
  const mark = sim ? <SimMark /> : null;

  if (layer.kind === "new") {
    return (
      <Drawer width={560} name="change" title="변경 요청 등록" sub={<><span>등록 → 승인 → 진행 → 종료 → 검토</span>{mark}</>} onClose={onClose}>
        <ChangeForm
          center={center}
          onDone={async (id) => {
            await onChanged();
            onOpen({ kind: "change", id });
          }}
        />
      </Drawer>
    );
  }
  if (layer.kind === "window") {
    return (
      <Drawer width={560} name="change" title="계획 작업 창 등록" sub={<><span>최대 24시간 · AI 보류는 창 안의 주의 경보만</span>{mark}</>} onClose={onClose}>
        <WindowForm
          center={center}
          onDone={async (id) => {
            await onChanged();
            onOpen({ kind: "windowDetail", id });
          }}
        />
      </Drawer>
    );
  }
  if (layer.kind === "windowDetail") {
    const w = windows.find((x) => x.id === layer.id);
    return (
      <Drawer width={560} name="change" title={w ? `계획 작업 창 ${w.id}` : "계획 작업 창"} sub={<><span>{w ? w.reason : "창을 찾지 못했습니다"}</span>{mark}</>} onClose={onClose}>
        {w ? <WindowDetail w={w} onChanged={onChanged} /> : <div className={detailStyles.empty}>이 창은 판에 없습니다</div>}
      </Drawer>
    );
  }
  const c = changes?.find((x) => x.id === layer.id) ?? null;
  return (
    <Drawer
      width={560}
      name="change"
      title={c ? c.title : "변경 요청"}
      lead={c ? <code className={styles.idTag}>{c.id}</code> : undefined}
      badge={c ? <ChangeChip state={c.state} /> : undefined}
      sub={c ? <><span>{c.target}</span><span>위험 {RISK_WORD[c.risk]}</span>{mark}</> : <><span>요청을 찾지 못했습니다</span>{mark}</>}
      onClose={onClose}
    >
      {c ? <ChangeDetail c={c} windows={windows} records={records} sim={sim} onChanged={onChanged} /> : <div className={detailStyles.empty}>이 요청은 판에 없습니다</div>}
    </Drawer>
  );
}

