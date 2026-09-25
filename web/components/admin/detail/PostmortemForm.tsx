"use client";

/**
 * 사후 분석 양식(관리자 설계 4-10-2). 해제된 사건에만 쓰고, 원인, 영향, 타임라인(사건 기록에서 고른 줄과 운영자가 더한 줄),
 * 재발 방지 조치(할 일, 담당, 기한, 완료)다. [초안 저장]과 [완료]는 BOT_OPS 이상이 누른다. 완료한 분석은 읽기만 한다.
 * sim은 관제 판에 저장해 모든 화면이 같은 분석을 보고, http는 서버 경로가 3라운드 계약 초안이라 칸 이름만 적고 입력을 막는다.
 */

import { useState } from "react";
import { AdminError, getAdminApi } from "@/lib/admin/api";
import { useCan } from "@/lib/admin/store";
import { hms, kstDay } from "@/lib/admin/timeText";
import type { Incident, IncidentEvent, Postmortem, PostmortemDraft } from "@/lib/admin/types";
import { toast } from "@/components/Toast";
import StatusShape from "../StatusShape";
import adminStyles from "../admin.module.css";
import controlStyles from "../control/control.module.css";
import styles from "./detail.module.css";

const SOURCE = "사후 분석(PUT /admin/v1/msp/incidents/{id}/postmortem · 3라운드 계약 초안)";

/** 처음 쓸 때 타임라인에 고를 기록. 알림과 판단 줄은 빼고 발생·확인·조치·해제의 뼈대만 먼저 고른다 */
const DEFAULT_KINDS = new Set<IncidentEvent["kind"]>(["OPENED", "ACKED", "APPROVED", "REJECTED", "AUTO_ACTED", "AUTO_REVERTED", "REVERTED", "SILENCED", "ACTED", "RESOLVED"]);

type Action = PostmortemDraft["actions"][number];

function Fields() {
  return (
    <ul className={styles.pmFields}>
      <li>
        원인<span>무엇이 왜 일어났는가</span>
      </li>
      <li>
        영향<span>누가 얼마나 겪었는가</span>
      </li>
      <li>
        타임라인<span>사건 기록에서 고른 줄과 더한 줄</span>
      </li>
      <li>
        재발 방지<span>할 일, 담당, 기한, 완료</span>
      </li>
    </ul>
  );
}

/** 완료한 분석. 읽기만 한다 */
function DoneView({ pm }: { pm: Postmortem }) {
  return (
    <div className={styles.pmDone}>
      <dl className={styles.pmRead}>
        <dt>원인</dt>
        <dd>{pm.cause}</dd>
        <dt>영향</dt>
        <dd>{pm.impact}</dd>
      </dl>
      <div className={styles.pmSub}>타임라인 · {pm.timeline.length}줄</div>
      <ol className={styles.pmTimeline}>
        {pm.timeline.map((l) => (
          <li key={`${l.at}-${l.text}`}>
            <span className={`${styles.rowTime} num`}>{hms(l.at)}</span>
            <span>{l.text}</span>
          </li>
        ))}
      </ol>
      <div className={styles.pmSub}>재발 방지 조치 · {pm.actions.length}건</div>
      <table className={`${styles.table} ${styles.pmActionsTable}`}>
        <thead>
          <tr>
            <th scope="col">할 일</th>
            <th scope="col">담당</th>
            <th scope="col" className={styles.num}>기한</th>
            <th scope="col">완료</th>
          </tr>
        </thead>
        <tbody>
          {pm.actions.map((a) => (
            <tr key={`${a.task}-${a.due}`}>
              <td>{a.task}</td>
              <td>{a.owner}</td>
              <td className={`${styles.num} num`}>{a.due}</td>
              <td>
                <span className={styles.pmDoneMark} data-done={a.done ? "" : undefined}>
                  <StatusShape kind={a.done ? "circle" : "ring"} />
                  {a.done ? "완료" : "진행"}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function PostmortemForm({ inc, events, operators, onSaved }: {
  inc: Incident;
  /** 이 사건의 기록. 오래된 것이 앞이다 */
  events: IncidentEvent[];
  /** 재발 방지 조치의 담당으로 고를 운영자 이름 */
  operators: string[];
  onSaved: () => void;
}) {
  const canEdit = useCan("BOT_OPS");
  const pm = inc.postmortem;
  const draft = pm && pm.status === "DRAFT" ? pm : null;
  // 초안의 타임라인 줄은 사건 기록과 시각·글이 같으면 그 기록을 고른 것으로, 아니면 운영자가 더한 줄로 읽는다
  const [picked, setPicked] = useState<Set<string>>(() => {
    if (draft) {
      return new Set(events.filter((e) => draft.timeline.some((l) => l.at === e.at && l.text === e.text)).map((e) => e.id));
    }
    return new Set(events.filter((e) => DEFAULT_KINDS.has(e.kind) && (e.kind !== "SILENCED" || e.actor !== "SYSTEM")).map((e) => e.id));
  });
  const [extra, setExtra] = useState<{ at: string; text: string }[]>(() =>
    draft ? draft.timeline.filter((l) => !events.some((e) => e.at === l.at && e.text === l.text)) : [],
  );
  const [cause, setCause] = useState(draft?.cause ?? "");
  const [impact, setImpact] = useState(draft?.impact ?? "");
  const [actions, setActions] = useState<Action[]>(() =>
    draft && draft.actions.length > 0
      ? draft.actions.map((a) => ({ ...a }))
      : [{ task: "", owner: inc.owner?.name ?? operators[0] ?? "", due: kstDay(Date.now() + 7 * 86_400_000), done: false }],
  );
  const [lineText, setLineText] = useState("");
  const [saving, setSaving] = useState(false);

  if (pm === undefined) {
    return (
      <div className={styles.postmortem}>
        <span className={controlStyles.notConnectedTitle}>
          <span aria-hidden="true">
            <StatusShape kind="dashed" />{" "}
          </span>
          사후 분석 원천 연결 전
        </span>
        <span className={controlStyles.notConnectedSource}>{SOURCE}. 입력과 저장을 받지 않습니다</span>
        <Fields />
      </div>
    );
  }
  if (inc.resolvedAt === null) {
    return (
      <div className={styles.postmortem} data-kind="wait">
        <span className={styles.pmWait}>사건이 해제되면 사후 분석을 씁니다</span>
        <Fields />
      </div>
    );
  }
  if (pm !== null && pm.status === "DONE") return <DoneView pm={pm} />;

  const timeline = [
    ...events.filter((e) => picked.has(e.id)).map((e) => ({ at: e.at, text: e.text })),
    ...extra,
  ].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  const filled = actions.filter((a) => a.task.trim().length > 0);
  const badDue = filled.some((a) => !/^\d{4}-\d{2}-\d{2}$/.test(a.due));
  const doneBlock = !canEdit
    ? "봇 운영 이상 권한 필요"
    : cause.trim() === "" || impact.trim() === ""
      ? "원인과 영향을 적어야 완료합니다"
      : filled.length === 0
        ? "재발 방지 조치 1건 이상 필요"
        : badDue
          ? "기한을 날짜로 적어야 합니다"
          : null;
  const saveBlock = !canEdit ? "봇 운영 이상 권한 필요" : badDue ? "기한을 날짜로 적어야 합니다" : null;

  const save = async (done: boolean) => {
    setSaving(true);
    try {
      await getAdminApi().savePostmortem(inc.id, { cause, impact, timeline, actions: filled }, done);
      toast(done ? "사후 분석을 완료했습니다" : "사후 분석 초안을 저장했습니다", "success");
      onSaved();
    } catch (e) {
      toast(e instanceof AdminError ? e.message : "사후 분석 저장 실패", "error");
    } finally {
      setSaving(false);
    }
  };
  const toggle = (id: string) =>
    setPicked((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const addLine = () => {
    const text = lineText.trim();
    if (!text) return;
    setExtra((cur) => [...cur, { at: new Date().toISOString(), text }]);
    setLineText("");
  };
  const setAction = (i: number, patch: Partial<Action>) => setActions((cur) => cur.map((a, n) => (n === i ? { ...a, ...patch } : a)));

  return (
    <form
      className={styles.pmForm}
      onSubmit={(e) => {
        e.preventDefault();
        if (doneBlock === null) void save(true);
      }}
    >
      <label className={`${adminStyles.field} ${styles.pmField}`}>
        <span className={styles.pmLabel}>원인</span>
        <textarea value={cause} onChange={(e) => setCause(e.target.value)} rows={3} maxLength={2000} disabled={!canEdit} placeholder="무엇이 왜 일어났는지 적습니다" />
      </label>
      <label className={`${adminStyles.field} ${styles.pmField}`}>
        <span className={styles.pmLabel}>영향</span>
        <textarea value={impact} onChange={(e) => setImpact(e.target.value)} rows={2} maxLength={2000} disabled={!canEdit} placeholder="누가 얼마나 겪었는지 적습니다" />
      </label>

      <fieldset className={styles.pmSet} disabled={!canEdit}>
        <legend className={styles.pmLabel}>
          타임라인 <span className={`${styles.pmCount} num`}>{timeline.length}줄</span>
        </legend>
        <ol className={styles.pmPick}>
          {events.map((e) => (
            <li key={e.id}>
              <label className={styles.pmPickRow}>
                <input type="checkbox" checked={picked.has(e.id)} onChange={() => toggle(e.id)} />
                <span className={`${styles.rowTime} num`}>{hms(e.at)}</span>
                <span className={styles.pmPickText}>{e.text}</span>
              </label>
            </li>
          ))}
          {extra.map((l, i) => (
            <li key={`x-${l.at}-${i}`}>
              <span className={styles.pmPickRow} data-extra="">
                <span className={styles.pmExtraMark} aria-hidden="true">+</span>
                <span className={`${styles.rowTime} num`}>{hms(l.at)}</span>
                <span className={styles.pmPickText}>{l.text}</span>
                <button type="button" className={styles.linkBtn} onClick={() => setExtra((cur) => cur.filter((_, n) => n !== i))}>
                  빼기
                </button>
              </span>
            </li>
          ))}
        </ol>
        <div className={styles.pmAddRow}>
          <input
            className={styles.pmInput}
            value={lineText}
            onChange={(e) => setLineText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addLine();
              }
            }}
            maxLength={200}
            placeholder="기록에 없는 일을 한 줄로 더합니다(지금 시각으로 적음)"
            aria-label="타임라인에 더할 줄"
          />
          <button type="button" className={adminStyles.neutralBtn} data-form="" onClick={addLine} disabled={lineText.trim() === ""}>
            줄 더하기
          </button>
        </div>
      </fieldset>

      <fieldset className={styles.pmSet} disabled={!canEdit}>
        <legend className={styles.pmLabel}>
          재발 방지 조치 <span className={`${styles.pmCount} num`}>{filled.length}건</span>
        </legend>
        <div className={styles.pmActions}>
          {actions.map((a, i) => (
            <div key={i} className={styles.pmActionRow}>
              <input className={`${styles.pmInput} ${styles.pmTask}`} value={a.task} onChange={(e) => setAction(i, { task: e.target.value })} maxLength={200} placeholder="할 일" aria-label={`재발 방지 조치 ${i + 1} 할 일`} />
              <select className={styles.pmSelect} value={a.owner} onChange={(e) => setAction(i, { owner: e.target.value })} aria-label={`재발 방지 조치 ${i + 1} 담당`}>
                {[...new Set([a.owner, ...operators].filter(Boolean))].map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
              <input className={`${styles.pmInput} num`} type="date" value={a.due} onChange={(e) => setAction(i, { due: e.target.value })} aria-label={`재발 방지 조치 ${i + 1} 기한`} />
              <label className={styles.pmCheck}>
                <input type="checkbox" checked={a.done} onChange={(e) => setAction(i, { done: e.target.checked })} />
                완료
              </label>
              <button type="button" className={`${styles.linkBtn} ${styles.pmRemove}`} onClick={() => setActions((cur) => cur.filter((_, n) => n !== i))} aria-label={`재발 방지 조치 ${i + 1} 빼기`}>
                빼기
              </button>
            </div>
          ))}
        </div>
        <button
          type="button"
          className={`${styles.linkBtn} ${styles.pmAddAction}`}
          onClick={() => setActions((cur) => [...cur, { task: "", owner: inc.owner?.name ?? operators[0] ?? "", due: kstDay(Date.now() + 7 * 86_400_000), done: false }])}
        >
          조치 더하기
        </button>
      </fieldset>

      <div className={styles.pmButtons}>
        {(doneBlock || saveBlock) && <span className={styles.pmBlock}>{doneBlock ?? saveBlock}</span>}
        <button type="button" className={adminStyles.neutralBtn} disabled={saveBlock !== null || saving} onClick={() => void save(false)}>
          초안 저장
        </button>
        <button type="submit" className={adminStyles.approveBtn} disabled={doneBlock !== null || saving}>
          완료
        </button>
      </div>
    </form>
  );
}
