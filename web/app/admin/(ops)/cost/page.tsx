"use client";

/**
 * 비용(/admin/cost, 관리자 설계 4-10-5). 예산과 크레딧 안에서 언제까지 운영할 수 있는지에 답한다. 위 전폭 비용 막대 판, 아래 두
 * 칸(계획 내역 | 학교 예산), 그 아래 app-3 가동 시간이다. 조작 단추가 없는 읽기 화면이고 계산식은 툴팁과 접근 이름이 싣는다.
 *
 * 계획값은 두 원천이 같은 정적 입력(lib/admin/cost.ts)이고 '계획 계산' 표식을 단다. 실제 사용액은 두 계정 모두 원천 연결 전이다
 * (AWS Cost Explorer. 개인 계정은 초안 GET /admin/v1/msp/cost, 학교는 운영자 영역). app-3 가동 시간만 sim이 관제 판에서 읽고, 용량·
 * 부하의 활동 기록과 같은 시간이다. 통화를 서로 바꾸지 않는다(환율 근거가 없다).
 */

import Link from "next/link";
import { app3Cost, COST_PLAN, COST_SOURCE, costView, krw, md, PLAN_PARTS, usd } from "@/lib/admin/cost";
import { useOpsCenter, useOpsPick } from "@/lib/admin/opsCenter";
import { spanWord } from "@/lib/admin/timeText";
import CostBar from "@/components/admin/CostBar";
import StatusShape from "@/components/admin/StatusShape";
import OpsHead from "@/components/admin/control/OpsHead";
import { NotConnected, PanelHead, SectionGap } from "@/components/admin/control/parts";
import { SimMark, StateChip } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import controlStyles from "@/components/admin/control/control.module.css";
import styles from "@/components/admin/cost/cost.module.css";

const PHASE_WORD = { BEFORE: "착수 전", RUNNING: "운영 중", AFTER: "운영 끝" } as const;

function Head() {
  const { data } = useOpsCenter();
  return <OpsHead title="비용" center={data} />;
}

/** app-3 가동 시간과 오늘의 계획 비용. 관제 판의 앱 계층(onSecondsToday)을 읽는다 */
function App3Panel() {
  const tier = useOpsPick((c) => c?.readTier ?? null);
  return (
    <section className={controlStyles.panel} aria-labelledby="cost-app3">
      <PanelHead id="cost-app3" title="app-3 가동 시간" meta="KST 오늘 · 기동·운영·드레이닝을 모두 셈" />
      {tier === null ? (
        <div className={styles.app3Skeleton} aria-busy="true">
          <span className="sr-only">관제 판을 읽는 중입니다</span>
        </div>
      ) : tier.state !== "READY" ? (
        <SectionGap section={tier} what="앱 계층" compact />
      ) : tier.data.onSecondsToday === null ? (
        <NotConnected what="app-3 가동 시간" source="앱 계층 활동 기록(로컬 조정기의 용량 API는 활동을 내지 않고 AWS 활동 기록은 읽지 않음)" compact />
      ) : (
        <div className={styles.app3}>
          <span className={styles.app3Figure}>
            <span className={styles.figLabel}>오늘 가동</span>
            <b className="num">{spanWord(tier.data.onSecondsToday * 1000)}</b>
          </span>
          <span className={styles.app3Figure} title={`가동 ${tier.data.onSecondsToday}초 × $${COST_PLAN.app3HourlyUsd}/시간`}>
            <span className={styles.figLabel}>오늘 app-3 계획 비용</span>
            <b className="num">{usd(app3Cost(tier.data.onSecondsToday))}</b>
          </span>
          <span className={styles.app3Figure}>
            <span className={styles.figLabel}>지금</span>
            <b>{tier.data.nodes[0]?.state === "OFF" ? "꺼짐" : tier.data.nodes[0]?.state === "IN_SERVICE" ? "운영 중" : tier.data.nodes[0]?.state === "PENDING" ? "기동 중" : "드레이닝"}</b>
          </span>
          <p className={styles.note}>
            단가 ${COST_PLAN.app3HourlyUsd}/시간(t3.small, gp3 20GiB, 공인 IPv4 한 개)에 가동 시간을 곱한 계획값입니다. 켜고 끈 기록은{" "}
            <Link href="/admin/capacity" className={controlStyles.stripLink}>
              용량·부하의 활동 기록
            </Link>
            에 있습니다.
          </p>
        </div>
      )}
    </section>
  );
}

export default function CostPage() {
  const now = useOpsPick((c) => (c ? Math.floor(Date.parse(c.generatedAt) / 60_000) * 60_000 : null)) ?? Date.now();
  const v = costView(COST_PLAN, now);
  const plan = COST_PLAN;
  const early = v.exhaustDay <= plan.endDay;
  return (
    <div className={adminStyles.page}>
      <Head />
      <div className={adminStyles.pageStack}>
        <section className={controlStyles.panel} aria-labelledby="cost-credit">
          <PanelHead
            id="cost-credit"
            title="개인 계정 크레딧"
            meta={
              <span className="num">
                {md(plan.startDay)}~{md(plan.endDay)} · {v.days}일 · {PHASE_WORD[v.phase]}
              </span>
            }
            actions={<SimMark kind="plan" />}
          />
          <div className={styles.creditBody}>
            <p className={styles.actual}>
              <StatusShape kind="dashed" className={styles.dash} />
              실제 사용 · AWS Cost Explorer 연결 전
            </p>
            <CostBar
              credit={v.credit}
              plan={v.planTotal}
              extra={v.planWithApp3 - v.planTotal}
              today={v.spentToDate}
              label={`크레딧 ${usd(v.credit)} 가운데 운영 종료일까지 계획 소진 ${usd(v.planTotal)}, app-3을 늘 켜면 ${usd(v.planWithApp3)}, 남는 크레딧 ${usd(v.remain)}`}
            />
            <ul className={styles.legend}>
              <li title={`하루 ${usd(v.daily)} × ${v.days}일`}>
                <span className={styles.swatch} data-kind="plan" aria-hidden="true" />
                계획 소진 <b className="num">{usd(v.planTotal)}</b>
              </li>
              <li title={`(하루 ${usd(v.dailyWithApp3)} − ${usd(v.daily)}) × ${v.days}일`}>
                <span className={styles.swatch} data-kind="extra" aria-hidden="true" />
                app-3 상시면 <b className="num">+{usd(v.planWithApp3 - v.planTotal)}</b>
              </li>
              <li title={`${usd(v.credit)} − ${usd(v.planTotal)}`}>
                <span className={styles.swatch} data-kind="rest" aria-hidden="true" />
                남는 크레딧 <b className="num">{usd(v.remain)}</b>
                <span className="num"> · app-3 상시 {usd(v.remainWithApp3)}</span>
              </li>
              <li>
                <span className={styles.tick} data-kind="today" aria-hidden="true" />
                오늘 <span className="num">{usd(v.spentToDate)}</span>
              </li>
              <li>
                <span className={styles.tick} data-kind="end" aria-hidden="true" />
                운영 종료 <span className="num">{md(plan.endDay)}</span>
              </li>
            </ul>
            <p className={styles.verdict}>
              {early ? (
                <StateChip tone="warning" label="종료일 전 소진 예상" />
              ) : (
                <StateChip tone="success" label="종료일까지 크레딧 안" />
              )}
              <span className="num" title={`${usd(v.credit)} ÷ 하루 ${usd(v.daily)}`}>
                계획 소진 예상 {md(v.exhaustDay)} · app-3 상시 {md(v.exhaustDayWithApp3)}
              </span>
              {v.phase === "BEFORE" && <span className={styles.before}>착수 전이라 막대는 계획만 그립니다</span>}
            </p>
          </div>
        </section>

        <div className={styles.grid}>
          <section className={controlStyles.panel} aria-labelledby="cost-plan">
            <PanelHead id="cost-plan" title="계획 내역" meta="2026-09-25 계획 계산" />
            <dl className={styles.facts}>
              <dt>크레딧</dt>
              <dd>
                <b className="num">{usd(v.credit)}</b>
                <span>{plan.creditNote}</span>
              </dd>
              <dt>운영 기간</dt>
              <dd>
                <b className="num">
                  {plan.startDay} ~ {plan.endDay}
                </b>
                <span className="num">{v.days}일(양 끝 포함) · 착수일은 계획입니다</span>
              </dd>
              <dt>하루 계획 비용</dt>
              <dd>
                <b className="num">{usd(v.daily)}</b>
                <span className="num">app-3을 늘 켜면 {usd(v.dailyWithApp3)}</span>
              </dd>
              <dt>{v.days}일 합</dt>
              <dd title={`${usd(v.daily)} × ${v.days}일 · ${usd(v.dailyWithApp3)} × ${v.days}일`}>
                <b className="num">{usd(v.planTotal)}</b>
                <span className="num">app-3 상시 {usd(v.planWithApp3)}</span>
              </dd>
              <dt>크레딧 소진</dt>
              <dd title={`착수일부터 하루 비용을 쌓아 ${usd(v.credit)}을 넘는 날`}>
                <b className="num">{md(v.exhaustDay)}</b>
                <span className="num">app-3 상시 {md(v.exhaustDayWithApp3)}</span>
              </dd>
              <dt>app-3 단가</dt>
              <dd>
                <b className="num">${plan.app3HourlyUsd}/시간</b>
                <span>t3.small, gp3 20GiB, 공인 IPv4 한 개 · AWS 작업 안내의 단가</span>
              </dd>
              <dt>구성</dt>
              <dd>
                <span className={styles.parts}>
                  {PLAN_PARTS.map((p) => (
                    <span key={p} className={styles.part}>
                      {p}
                    </span>
                  ))}
                </span>
                <span>항목별 금액은 계산 내역(볼륨 크기, bot-1 규격, 공인 IPv4 수)이 저장소에 없어 적지 않습니다</span>
              </dd>
              <dt>넣지 않은 것</dt>
              <dd>
                <span>{plan.excluded.join(", ")}</span>
              </dd>
            </dl>
          </section>

          <section className={controlStyles.panel} aria-labelledby="cost-school">
            <PanelHead id="cost-school" title="학교 계정 예산" meta="월 단위" />
            <dl className={styles.facts}>
              <dt>운영 예산</dt>
              <dd>
                <b className="num">{krw(plan.school.monthlyKrw)}</b>
                <span>{plan.school.note}</span>
              </dd>
              <dt>참고 계산</dt>
              <dd>
                <b className="num">${plan.school.reference.usd}</b>
                <span>{plan.school.reference.label} · 환율 근거가 없어 원화로 바꾸지 않습니다</span>
              </dd>
            </dl>
            <NotConnected what="실제 청구액" source="학교 계정의 Cost Explorer와 Budgets는 운영자 영역이라 읽지 않습니다(ADR 0029)" compact />
            <NotConnected what="개인 계정 실제 사용" source={COST_SOURCE} compact />
          </section>
        </div>

        <App3Panel />
      </div>
    </div>
  );
}
