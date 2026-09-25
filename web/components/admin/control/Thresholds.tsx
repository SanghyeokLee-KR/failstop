"use client";

/**
 * 임계값 관리. Grafana로는 할 수 없는 일(값을 바꾸고, 승인하고, 되돌리는 일)을 가장 분명하게
 * 보이는 자리다(관리자 설계 4-4). 값을 바꾸면 변경 전후와 영향이 서고, 변경 요청은 기존 1인 승인
 * 흐름(ApprovalList)으로 이어진다. 되돌리기도 같은 승인을 거친다.
 */

import { memo, useState } from "react";
import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { stepKey } from "@/lib/admin/thresholdKeys";
import type { ApprovalPayload, ControlCenter, OpsSection, OpsThreshold, ThresholdChange, ThresholdRegistry, ThresholdVia } from "@/lib/admin/types";
import ApprovalList from "../ApprovalList";
import RequestButton from "../RequestButton";
import GrafanaLink from "../GrafanaLink";
import { hms, NotConnected, PanelHead } from "./parts";
import styles from "./control.module.css";
import StatusShape from "../StatusShape";

const VIA_WORD: Record<ThresholdVia, string> = {
  MANUAL: "수동",
  AI_APPROVED: "AI 제안 승인",
  AI_AUTO: "AI 자동",
  REVERT: "되돌리기",
};
/** 증설 임계는 축소 임계보다 이만큼 높아야 한다(시뮬레이터와 같은 규칙) */
const SCALE_GAP = 15;

function checkDraft(t: OpsThreshold, to: number, items: OpsThreshold[]): string | null {
  if (!Number.isFinite(to)) return "숫자 입력 필요";
  if (to < t.min || to > t.max) return `허용 범위 ${t.min}~${t.max}${t.unit} 밖`;
  const val = (k: OpsThreshold["key"]) => items.find((x) => x.key === k)?.value ?? 0;
  const out = t.key === "read.scale_out_cpu" ? to : val("read.scale_out_cpu");
  const inn = t.key === "read.scale_in_cpu" ? to : val("read.scale_in_cpu");
  if ((t.key === "read.scale_out_cpu" || t.key === "read.scale_in_cpu") && out - inn < SCALE_GAP) {
    return `증설 임계는 축소 임계보다 ${SCALE_GAP}%p 이상 높아야 함`;
  }
  return null;
}

/** 현재값에 새 임계를 적용했을 때 무엇이 달라지는지. 판단 근거를 값으로 적는다 */
function impactOf(t: OpsThreshold, to: number, c: ControlCenter): string {
  const tier = c.readTier.state === "READY" ? c.readTier.data : null;
  const hosts = c.hosts.state === "READY" ? c.hosts.data : [];
  const lower = to < t.value;
  const count = (pred: (v: number) => boolean, pick: (h: (typeof hosts)[number]) => number | null) =>
    hosts.filter((h) => {
      const v = pick(h);
      return v !== null && pred(v);
    }).length;
  switch (t.key) {
    case "read.scale_out_cpu": {
      const avg = tier?.avgCpuPct ?? null;
      if (avg !== null && avg >= to && avg < t.value) return `지금 앱 평균 CPU가 ${avg}%라 적용 즉시 증설 경보가 울리고 AI가 app-3 켜기를 제안합니다.`;
      return lower ? "app-3가 더 일찍 켜집니다. 급증 때 지연 초과가 줄지만 평시에 켜지는 일이 잦아져 비용이 늘 수 있습니다." : "app-3가 더 늦게 켜집니다. 급증 때 응답 지연이 길어질 수 있습니다.";
    }
    case "read.scale_in_cpu":
      return lower ? "app-3가 더 늦게 꺼집니다. 비용 절감이 줄고 여유는 늘어납니다." : "app-3가 더 일찍 꺼집니다. 비용이 줄지만 다시 몰릴 때 도로 켜는 일이 잦아질 수 있습니다.";
    case "host.cpu_alert": {
      const now = count((v) => v >= t.value, (h) => h.cpuPct);
      const next = count((v) => v >= to, (h) => h.cpuPct);
      return `현재값 기준으로 CPU 경보 노드가 ${now}대에서 ${next}대가 됩니다.`;
    }
    case "host.mem_alert": {
      const now = count((v) => v >= t.value, (h) => h.memPct);
      const next = count((v) => v >= to, (h) => h.memPct);
      return `현재값 기준으로 메모리 경보 노드가 ${now}대에서 ${next}대가 됩니다.`;
    }
    case "host.disk_free_alert": {
      const now = count((v) => 100 - v < t.value, (h) => h.diskUsedPct);
      const next = count((v) => 100 - v < to, (h) => h.diskUsedPct);
      return `현재값 기준으로 디스크 여유 경보 노드가 ${now}대에서 ${next}대가 됩니다. 디스크 정리는 금지 목록이라 AI가 하지 않습니다.`;
    }
    case "db.replication_lag_alert":
      return lower ? "복제 지연을 더 일찍 알립니다. 비동기 복제라 지연 동안의 기록은 Primary를 잃으면 함께 잃을 수 있습니다." : "복제 지연을 더 늦게 알립니다. 유실 위험 구간을 늦게 알게 됩니다.";
    case "backup.delay_alert": {
      const b = c.jobs.state === "READY" ? c.jobs.data.backup.lastSuccessAt : null;
      const h = b === null ? null : Math.floor((Date.now() - Date.parse(b)) / 3_600_000);
      if (h !== null && h >= to && h < t.value) return `마지막 백업이 ${h}시간 전이라 적용 즉시 백업 지연 경보가 울리고 AI가 즉시 백업을 제안합니다.`;
      return lower ? "백업 지연을 더 일찍 알립니다." : "백업 지연을 더 늦게 알립니다.";
    }
    case "bot.capacity_guard": {
      const bot = hosts.find((h) => h.id === "bot-1")?.cpuPct ?? null;
      if (bot !== null && bot >= to && bot < t.value) return `bot-1 CPU가 ${bot}%라 적용 즉시 봇 용량 보호가 걸리고 AI가 봇 부하 줄임을 제안합니다.`;
      return lower ? "봇 부하를 더 일찍 줄입니다. 관측 수집 여유를 더 지킵니다." : "봇 부하를 더 늦게 줄입니다.";
    }
    case "host.cpu_credit_alert": {
      const now = count((v) => v < t.value, (h) => h.cpuCredits);
      const next = count((v) => v < to, (h) => h.cpuCredits);
      return `현재값 기준으로 크레딧 경보 노드가 ${now}대에서 ${next}대가 됩니다. 정보 등급이라 AI가 30분 보류를 제안할 수 있습니다.`;
    }
  }
}

function payloadOf(t: OpsThreshold, to: number, reason: string, revertOf: string | null): ApprovalPayload {
  return { action: "ops.threshold.set", key: t.key, label: t.label, unit: t.unit, from: t.value, to, reason, revertOf, proposalId: null };
}

function HistoryTable({ history, items, limit }: { history: ThresholdChange[]; items: OpsThreshold[]; limit?: number }) {
  const rows = limit ? history.slice(0, limit) : history;
  if (rows.length === 0) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyMark} aria-hidden="true" />
        변경 이력 없음 · 초기값 그대로
      </div>
    );
  }
  return (
    <table className={styles.hist}>
      <thead>
        <tr>
          <th>시각</th>
          <th>항목</th>
          <th>변경</th>
          <th>경로</th>
          <th>누가</th>
          <th>
            <span className="sr-only">되돌리기</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((h) => {
          const t = items.find((x) => x.key === h.key);
          // 지금 값이 이 변경의 결과일 때만 되돌린다. 뒤에 다른 변경이 얹혔으면 그 변경부터 되돌려야 한다
          const canRevert = t !== undefined && !h.reverted && h.via !== "REVERT" && t.value === h.to;
          return (
            <tr key={h.id}>
              <td className="num">{hms(h.at)}</td>
              <td>{h.label}</td>
              <td className="num">
                <b>
                  {h.from} → {h.to}
                </b>
                {h.unit}
              </td>
              <td>
                <span className={styles.via} data-via={h.via}>
                  {VIA_WORD[h.via]}
                </span>
              </td>
              <td>{h.by}</td>
              <td>
                {canRevert && t ? (
                  <RequestButton
                    action="ops.threshold.set"
                    payload={payloadOf(t, h.from, "이력에서 되돌리기", h.id)}
                    label="되돌리기"
                    confirm={{
                      title: "임계값 되돌리기 요청",
                      description: "되돌리기도 같은 1인 승인을 거칩니다. 발급하고 실행하면 적용됩니다.",
                      target: `${h.label} ${h.to}${h.unit} → ${h.from}${h.unit}`,
                      blastRadius: "승인 목록에서 발급·실행",
                      tone: "warning",
                      confirmLabel: "되돌리기 요청",
                    }}
                  />
                ) : h.reverted ? (
                  <span className={styles.none}>되돌림</span>
                ) : null}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** 변경 검토의 영향 문장. 지금 판의 값으로 계산하므로 편집하는 동안 이 조각만 판을 따라 다시 그린다 */
function Impact({ t, to }: { t: OpsThreshold; to: number }) {
  const { data } = useOpsCenter();
  return <>{data ? impactOf(t, to, data) : "영향 계산 중"}</>;
}

/** 확인 대화상자에 실을 영향 문장. 누를 때 한 번 계산한다 */
function ImpactRequest({ t, to, onDone }: { t: OpsThreshold; to: number; onDone: () => void }) {
  const { data } = useOpsCenter();
  return (
    <RequestButton
      action="ops.threshold.set"
      payload={payloadOf(t, to, "운영자 변경", null)}
      label="변경 요청"
      onRequested={onDone}
      confirm={{
        title: "임계값 변경 요청",
        description: data ? impactOf(t, to, data) : undefined,
        target: `${t.label} ${t.value}${t.unit} → ${to}${t.unit}`,
        blastRadius: "승인 목록에서 발급·실행 뒤 적용",
        tone: "warning",
        confirmLabel: "변경 요청",
      }}
    />
  );
}

/** 임계값 칸만 받는다. 표는 값이 바뀔 때만 다시 그리고, 편집 중의 영향 문장만 판을 따라 움직인다 */
function ThresholdPanel({ section, onChanged, full = false }: {
  /** 판을 받기 전이면 null이다 */
  section: OpsSection<ThresholdRegistry> | null;
  onChanged: () => void;
  full?: boolean;
}) {
  const [draft, setDraft] = useState<{ key: OpsThreshold["key"]; value: number } | null>(null);
  const reg = section?.state === "READY" ? section.data : null;
  const editing = reg && draft ? reg.items.find((t) => t.key === draft.key) ?? null : null;
  const dirty = editing !== null && draft !== null && draft.value !== editing.value;
  const error = editing && draft ? checkDraft(editing, draft.value, reg?.items ?? []) : null;

  return (
    <section className={styles.panel} aria-labelledby="th-title">
      <PanelHead
        id="th-title"
        title="임계값 관리"
        meta="게이지 눈금과 경보 판정이 이 값을 그대로 씁니다"
        actions={
          <>
            <span className={styles.grafanaNote}>
              <span>
                <b>Grafana</b>는 보기만 · 변경·승인·되돌리기는 여기서
              </span>
            </span>
            {!full && (
              <Link href="/admin/thresholds" className={styles.stripLink}>
                전체 이력
              </Link>
            )}
            <GrafanaLink board="msp-overview" label="경보 추이" />
          </>
        }
      />
      {section === null ? null : section.state === "NOT_CONNECTED" ? (
        <NotConnected what="임계값 레지스트리" source={section.source} />
      ) : reg === null ? null : (
        <div className={styles.thGrid}>
          <table className={styles.thTable}>
            <thead>
              <tr>
                <th>항목</th>
                <th data-num="">현재값</th>
                <th data-num="">허용 범위</th>
                <th>AI 제안</th>
                <th>편집</th>
              </tr>
            </thead>
            <tbody>
              {reg.items.map((t) => {
                const mine = draft?.key === t.key;
                const value = mine && draft ? draft.value : t.value;
                return (
                  <tr key={t.key} data-draft={mine && dirty ? "" : undefined}>
                    <td>
                      <span className={styles.thName}>
                        <b>{t.label}</b>
                        <span>{t.appliesTo}</span>
                      </span>
                    </td>
                    <td className={`${styles.thValue} num`}>
                      {t.value}
                      <small>{t.unit}</small>
                    </td>
                    <td className={`${styles.thRange} num`}>
                      {t.min}~{t.max}
                      {t.unit}
                    </td>
                    <td>
                      {t.aiSuggested !== null ? (
                        <span className={styles.aiValue} title={t.aiReason ?? undefined}>
                          AI <span className="num">{t.aiSuggested}{t.unit}</span>
                        </span>
                      ) : (
                        <span className={styles.none}>없음</span>
                      )}
                    </td>
                    <td>
                      <label className={styles.editor}>
                        {/* 슬라이더는 끌기 전용이다. 건반 초점은 옆 숫자 칸 하나가 받는다 */}
                        <input
                          type="range"
                          tabIndex={-1}
                          min={t.min}
                          max={t.max}
                          step={t.step}
                          value={value}
                          aria-label={`${t.label} 새 값 슬라이더`}
                          onChange={(e) => setDraft({ key: t.key, value: Number(e.target.value) })}
                        />
                        <input
                          type="number"
                          min={t.min}
                          max={t.max}
                          step={t.step}
                          value={value}
                          aria-label={`${t.label} 새 값`}
                          aria-keyshortcuts="PageUp PageDown Escape"
                          onChange={(e) => setDraft({ key: t.key, value: Number(e.target.value) })}
                          onKeyDown={(e) => {
                            if (e.key === "Escape" && mine) {
                              e.preventDefault();
                              setDraft(null);
                              return;
                            }
                            const next = stepKey(e.key, value, t);
                            if (next === null) return;
                            e.preventDefault();
                            setDraft({ key: t.key, value: next });
                          }}
                        />
                      </label>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className={styles.stack} style={{ gap: "var(--space-3)" }}>
            {editing && draft && dirty ? (
              <div className={styles.review}>
                <div className={styles.subHead}>변경 검토 · {editing.label}</div>
                <div className={`${styles.reviewDiff} num`}>
                  <s>
                    {editing.value}
                    {editing.unit}
                  </s>
                  <span aria-hidden="true">→</span>
                  <span>
                    {draft.value}
                    {editing.unit}
                  </span>
                  <span className="sr-only">
                    {editing.value}에서 {draft.value}로
                  </span>
                </div>
                <p className={styles.reviewText}>
                  <b>영향</b> · <Impact t={editing} to={draft.value} />
                </p>
                <p className={styles.reviewText}>
                  <b>적용</b> · 변경 요청 뒤 승인 목록에서 발급하고 실행하면 적용됩니다. 이력에서 되돌릴 수 있습니다.
                </p>
                {error && (
                  <p className={styles.reviewError}>
                    <StatusShape kind="square" /> {error}
                  </p>
                )}
                <div className={styles.reviewActions}>
                  {error === null && <ImpactRequest t={editing} to={draft.value} onDone={() => setDraft(null)} />}
                  <button type="button" className={styles.smallBtn} onClick={() => setDraft(null)}>
                    취소
                  </button>
                </div>
              </div>
            ) : (
              <div className={styles.review} data-empty="">
                <div className={styles.subHead}>변경 검토</div>
                <p className={styles.reviewText}>
                  왼쪽에서 값을 바꾸면 변경 전후와 영향이 여기에 나타납니다. AI 제안값에는 AI 표식이 붙습니다.
                </p>
              </div>
            )}
            <div className={styles.subHead}>승인 대기</div>
            <ApprovalList
              actions={["ops.threshold.set"]}
              onChange={onChanged}
              emptyLabel="대기 중인 임계값 변경 없음"
              emptyDesc="변경을 요청하면 여기서 승인을 발급하고 실행합니다"
            />
            <div className={styles.subHead}>변경 이력</div>
            <HistoryTable history={reg.history} items={reg.items} limit={full ? undefined : 4} />
          </div>
        </div>
      )}
    </section>
  );
}

export default memo(ThresholdPanel);
