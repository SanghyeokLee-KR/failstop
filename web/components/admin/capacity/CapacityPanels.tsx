"use client";

/**
 * 용량·부하의 확장 칸(관리자 설계 4-10-3). CPU 크레딧, 용량 전망, 앱 계층 활동 기록, 부하 시험 기록이다. 관제 판의 칸만 골라
 * 구독해(useOpsPick) 지금 판(수동 갱신)과 따로 움직인다.
 *
 * CPU 크레딧은 t3.small의 한 식이다(credits.ts). 기준선 vCPU당 20%보다 높게 쓰면 잔량이 준다. 학교 노드는 크레딧 모드
 * Standard라 잔량이 0이면 추가 과금 없이 기준선으로 묶인다. Unlimited로 바꾸면 모자란 몫을 과금하고 CloudWatch
 * CPUSurplusCreditsCharged로 드러나므로 그 경보 자리를 원천 연결 전으로 둔다. 용량 전망과 '0까지' 시간은 예측이라 칸 머리에
 * 근거 구간을 적고, 근거가 24시간보다 짧으면 예측하지 않는다. 부하 시험 기록은 모두 로컬 Docker 측정이라 AWS 성능이 아니다.
 */

import { creditNetPerHour, CREDIT_SOURCE, hoursToEmpty, SURPLUS_SOURCE, T3_SMALL } from "@/lib/admin/credits";
import { useOpsDetail } from "@/lib/admin/detailStore";
import { LOAD_TESTS } from "@/lib/admin/loadTests";
import { useOpsPick } from "@/lib/admin/opsCenter";
import { onSecondsFromActivity, tookWord, VIA_WORD } from "@/lib/admin/tierActivity";
import { durationWord, hms, kstDay, mdhm } from "@/lib/admin/timeText";
import type { CapacityOutlook, OpsHost, OpsThreshold } from "@/lib/admin/types";
import { bytesWord } from "@/lib/admin/dbFacts";
import { CommandChip } from "../CommandChip";
import KeepNames from "../KeepNames";
import StatusShape from "../StatusShape";
import { MeterRail } from "../instruments/MeterRail";
import { NotConnected, PanelHead, SectionGap, useClock } from "../control/parts";
import controlStyles from "../control/control.module.css";
import styles from "./capacity.module.css";

function thresholdOf(items: OpsThreshold[] | null, key: OpsThreshold["key"]): number | null {
  return items?.find((t) => t.key === key)?.value ?? null;
}

/** 남은 시간(예측). 하루 이상은 일, 그 아래는 시간이다 */
function hoursWord(h: number): string {
  if (h >= 48) return `약 ${Math.floor(h / 24)}일`;
  if (h >= 1) return `약 ${Math.floor(h)}시간`;
  return `약 ${Math.max(1, Math.round(h * 60))}분`;
}

function CreditRow({ h, alert }: { h: OpsHost; alert: number | null }) {
  const v = h.cpuCredits;
  const low = v !== null && alert !== null && v < alert;
  const net = h.cpuPct === null ? null : creditNetPerHour(h.cpuPct);
  const empty = v === null || h.cpuPct === null ? null : hoursToEmpty(v, h.cpuPct);
  return (
    <div className={styles.creditRow}>
      <span className={styles.host}>{h.id}</span>
      <span aria-hidden="true">
        <MeterRail value={v ?? 0} max={T3_SMALL.maxCredits} threshold={alert ?? undefined} tone={low ? "warning" : undefined} />
      </span>
      <span className={`${styles.value} num`} data-tone={low ? "warning" : undefined}>
        {v === null ? (
          <span className={styles.missing} title={CREDIT_SOURCE}>
            ◌
          </span>
        ) : (
          <>
            {low && (
              <>
                <StatusShape kind="triangle" size={9} />{" "}
              </>
            )}
            {v}
          </>
        )}
      </span>
      <span className={`${styles.net} num`} data-dir={net !== null && net < 0 ? "down" : "up"}>
        {net === null ? <span className={styles.missing}>◌</span> : `${net >= 0 ? "+" : "−"}${Math.abs(net).toFixed(1)}/시간`}
      </span>
      <span className={`${styles.forecast} num`}>{v === null || net === null ? <span className={styles.missing}>◌</span> : empty === null ? "쌓는 중" : `0까지 ${hoursWord(empty)}`}</span>
      <span className="sr-only">
        {h.id} 크레딧 {v === null ? "원천 연결 전" : `${v}개`}
        {alert !== null && `, 경보 기준 ${alert}개 미만`}
        {low && ", 주의"}
        {net !== null && `, 시간당 ${net >= 0 ? "적립" : "소모"} ${Math.abs(net).toFixed(1)}개`}
      </span>
    </div>
  );
}

export function CreditPanel() {
  const hosts = useOpsPick((c) => c?.hosts ?? null);
  const thresholds = useOpsPick((c) => (c?.thresholds.state === "READY" ? c.thresholds.data.items : null));
  const alert = thresholdOf(thresholds, "host.cpu_credit_alert");
  const t3 = hosts?.state === "READY" ? hosts.data.filter((h) => h.instanceType.startsWith("t3")) : [];
  const anyValue = t3.some((h) => h.cpuCredits !== null);
  return (
    <section className={controlStyles.panel} aria-labelledby="cap-credits">
      <PanelHead id="cap-credits" title="CPU 크레딧" meta={`t3.small · 기준선 vCPU당 ${T3_SMALL.baselinePct}% · 시간당 ${T3_SMALL.earnPerHour}개 적립 · 최대 ${T3_SMALL.maxCredits}개`} />
      {hosts === null ? null : hosts.state !== "READY" ? (
        <SectionGap section={hosts} what="호스트 지표" />
      ) : (
        <>
          <div className={styles.credits} role="group" aria-label="노드별 CPU 크레딧">
            <div className={styles.creditHead} aria-hidden="true">
              <span>노드</span>
              <span className={styles.span2}>잔량</span>
              <span>증감</span>
              <span>예측</span>
            </div>
            {t3.map((h) => (
              <CreditRow key={h.id} h={h} alert={alert} />
            ))}
          </div>
          {anyValue ? (
            <p className={styles.creditNote}>막대의 눈금은 임계값 관리의 CPU 크레딧 경보 기준{alert !== null ? `(${alert}개)` : ""}입니다</p>
          ) : (
            <NotConnected what="CPU 크레딧 잔량" source={CREDIT_SOURCE} compact />
          )}
          <div className={styles.creditFacts}>
            <p className={styles.fact}>
              <span className={styles.factKey}>계산</span>
              <span>시간당 증감 = 적립 {T3_SMALL.earnPerHour} − CPU% × vCPU {T3_SMALL.vcpu} × 60분. CPU가 기준선보다 높으면 잔량이 줄고, 예측은 지금 CPU가 이어질 때입니다</span>
            </p>
            <p className={styles.fact}>
              <span className={styles.factKey}>크레딧 모드</span>
              <span>학교 노드는 Standard입니다. 잔량이 0이면 추가 과금 없이 CPU가 기준선으로 묶입니다</span>
            </p>
            <div className={styles.fact}>
              <span className={styles.factKey}>Unlimited 과금 경보</span>
              <span>Unlimited로 바꾸면 기준선을 넘겨 쓴 몫을 과금합니다. CPUSurplusCreditsCharged가 0보다 크면 경보를 세웁니다</span>
            </div>
            <NotConnected what="추가 과금 경보" source={SURPLUS_SOURCE} compact />
          </div>
        </>
      )}
    </section>
  );
}

/** 예상 날짜. 올해면 월.일, 해를 넘기면 연도까지 적는다(KST) */
function dayWord(at: string, now: number): string {
  const [y, m, d] = kstDay(Date.parse(at)).split("-").map(Number);
  return kstDay(now).slice(0, 4) === String(y) ? `${m}.${d}` : `${y}.${m}.${d}`;
}

/** 예상 시각 칸. 며칠 뒤와 날짜를 두 줄로 적는다. 없으면 까닭을 적는다 */
function When({ at, now }: { at: string | null; now: number }) {
  if (at === null) return <span className={styles.missing}>없음</span>;
  const days = Math.max(0, Math.floor((Date.parse(at) - now) / 86_400_000));
  return (
    <span className={styles.when}>
      <span>{days}일 뒤</span>
      <span className={styles.whenSub}>{dayWord(at, now)}</span>
    </span>
  );
}

export function OutlookPanel() {
  const outlook = useOpsPick((c) => c?.outlook ?? null);
  const now = useClock(60_000);
  const basis = outlook?.state === "READY" ? outlook.data.basisHours : null;
  const enough = basis !== null && basis >= 24;
  return (
    <section className={controlStyles.panel} aria-labelledby="cap-outlook">
      <PanelHead
        id="cap-outlook"
        title="용량 전망"
        actions={basis !== null ? <span className={`${styles.forecastMark} num`}>{enough ? `예측 · 최근 ${basis}시간 추세` : "표본 부족"}</span> : undefined}
      />
      {outlook === null ? null : outlook.state !== "READY" ? (
        <SectionGap section={outlook} what="용량 전망" />
      ) : (
        <OutlookBody o={outlook.data} enough={enough} now={now} />
      )}
    </section>
  );
}

function OutlookBody({ o, enough, now }: { o: CapacityOutlook; enough: boolean; now: number }) {
  return (
    <>
      <div role="group" aria-label="노드별 디스크 전망">
        <div className={styles.diskHead} aria-hidden="true">
          <span>노드</span>
          <span>디스크 사용률</span>
          <span>하루 증가</span>
          <span>경보 기준</span>
          <span>가득 참</span>
        </div>
        {o.disks.map((d) => (
          <div key={d.host} className={styles.diskRow}>
            <span className={styles.hostCell}>
              <span className={styles.host}>{d.host}</span>
              {d.sizeGiB !== null && <span className={`${styles.whenSub} num`}>{d.sizeGiB}GiB</span>}
            </span>
            <span className={styles.diskUsed}>
              <span className="num">{d.usedPct}%</span>
              <span aria-hidden="true">
                <MeterRail value={d.usedPct} max={100} />
              </span>
            </span>
            <span className="num">{d.growthPctPerDay === null ? <span className={styles.missing}>◌</span> : `${d.growthPctPerDay.toFixed(1)}%p`}</span>
            <span className="num">{enough ? <When at={d.alertAt} now={now} /> : <span className={styles.missing}>표본 부족</span>}</span>
            <span className="num">{enough ? <When at={d.fullAt} now={now} /> : <span className={styles.missing}>표본 부족</span>}</span>
            <span className="sr-only">
              {d.host} 디스크 {d.usedPct}%, 하루 {d.growthPctPerDay ?? "미수집"}%p 증가
              {enough && d.alertAt && `, 경보 기준 도달 예측 ${mdhm(d.alertAt)}`}
              {enough && d.fullAt && `, 가득 참 예측 ${mdhm(d.fullAt)}`}
            </span>
          </div>
        ))}
      </div>
      <div className={styles.outlookFoot}>
        <span>
          DB 하루 증가 <b className="num">{o.dbGrowthBytesPerDay === null ? "◌" : `약 ${bytesWord(o.dbGrowthBytesPerDay)}`}</b> · 데이터베이스 화면의 DB 크기 증가와 같은 값
        </span>
        <span className={styles.outlookNote}>경보 기준은 임계값 관리의 디스크 여유 경보입니다. 하루 1~2GB 같은 추정은 측정값으로 쓰지 않습니다</span>
      </div>
    </>
  );
}

export function ActivityPanel() {
  const outlook = useOpsPick((c) => c?.outlook ?? null);
  const onSeconds = useOpsPick((c) => (c?.readTier.state === "READY" ? c.readTier.data.onSecondsToday : null));
  const now = useClock(30_000);
  const today = kstDay(now);
  const list = outlook?.state === "READY" ? outlook.data.activity : null;
  // 활동 기록에서 센 가동 시간과 판의 가동 시간은 같은 규칙이다. 판의 값을 적고 기록이 판과 다르면 함께 적지 않는다
  const fromLog = list ? onSecondsFromActivity(list, now) : null;
  const open = (id: string) => useOpsDetail.getState().openIncident(id);
  return (
    <section className={controlStyles.panel} aria-labelledby="cap-activity">
      <PanelHead
        id="cap-activity"
        title="앱 계층 활동 기록"
        meta={
          onSeconds !== null ? (
            <span className="num">
              오늘 app-3 가동 {durationWord(onSeconds * 1000)} · 기동·드레이닝 포함 · 비용 화면과 같은 값
            </span>
          ) : undefined
        }
      />
      {outlook === null ? null : outlook.state !== "READY" || list === null ? (
        outlook.state !== "READY" ? <SectionGap section={outlook} what="앱 계층 활동 기록" /> : null
      ) : list.length === 0 ? (
        <div className={controlStyles.empty}>
          <span className={controlStyles.emptyMark} aria-hidden="true" />
          app-3 켜기·끄기 기록 없음
        </div>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <colgroup>
              <col className={styles.cAt} />
              <col className={styles.cAct} />
              <col className={styles.cWho} />
              <col />
              <col className={styles.cTook} />
              <col className={styles.cInc} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">조치</th>
                <th scope="col">행위자</th>
                <th scope="col">이유</th>
                <th scope="col">걸린 시간</th>
                <th scope="col">사건</th>
              </tr>
            </thead>
            <tbody>
              {list.map((a) => (
                <tr key={a.id} data-link={a.incidentId ? "" : undefined} onClick={a.incidentId ? () => open(a.incidentId as string) : undefined}>
                  <td className="num">{kstDay(Date.parse(a.at)) === today ? hms(a.at) : mdhm(a.at)}</td>
                  <td>
                    <span className={styles.act} data-action={a.action}>
                      <StatusShape kind={a.action === "ON" ? "play" : "ring"} size={9} />
                      {a.action === "ON" ? "켜기" : "끄기"}
                    </span>
                  </td>
                  <td>
                    <span className={styles.who}>{VIA_WORD[a.via]}</span>
                    <span className={styles.whoSub}>{a.actorName}</span>
                  </td>
                  <td>
                    <span className={styles.cell} title={a.reason}>
                      <KeepNames text={a.reason} />
                    </span>
                  </td>
                  <td>
                    <span className={`${styles.took} num`} data-stopped={a.interrupted ? "" : undefined}>
                      {tookWord(a)}
                    </span>
                  </td>
                  <td>
                    {a.incidentId ? (
                      <button
                        type="button"
                        className={styles.incLink}
                        aria-haspopup="dialog"
                        onClick={(e) => {
                          e.stopPropagation();
                          open(a.incidentId as string);
                        }}
                      >
                        {a.incidentId}
                      </button>
                    ) : (
                      <span className={styles.none}>사건 밖</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {fromLog !== null && onSeconds !== null && Math.abs(fromLog - onSeconds) > 2 && (
        <p className={styles.outlookNote}>기록이 40줄을 넘어 앞의 켜기·끄기가 빠졌습니다. 가동 시간은 관제 판의 값입니다</p>
      )}
    </section>
  );
}

export function LoadTestPanel() {
  return (
    <section className={controlStyles.panel} aria-labelledby="cap-tests">
      <PanelHead id="cap-tests" title="부하 시험 기록" meta="로컬 Docker 측정은 AWS 성능이 아닙니다 · 문서 경로를 복사합니다" />
      <ul className={styles.tests}>
        {LOAD_TESTS.map((t) => (
          <li key={t.day} className={styles.test}>
            <span className={`${styles.testDay} num`}>{t.day}</span>
            <span className={styles.testText}>
              {t.capacity && <span className={styles.capacityTag}>용량 판단</span>}
              <KeepNames text={t.summary} />
            </span>
            <CommandChip command={t.path} kind="path" label={t.path.split("/").pop()} />
          </li>
        ))}
      </ul>
    </section>
  );
}
