/**
 * 재생 소요 미니 막대(복구 이력 패널 헤더 계기). 최근 20건을 시간순으로 새긴다.
 *
 * 입력은 recovery_runs.duration_ms(저널 재생에 걸린 시간)다. 페일오버 RTO가 아니다.
 * 두 축은 다른 것을 재는데 종전에는 RTO SLO 30초를 이 그림의 기준선으로 그어 겹쳤다.
 * RTO는 harness.failover_runs.rto_seconds가 원천이고 그 판정은 verdict.failover_ok가 한다.
 * 스케일은 표본 최댓값을 덮는 이산 단계 창이다(아래 SCALE_STEPS_MS 근거).
 * 자기 최댓값 정규화는 최고 막대가 항상 천장에 닿아 시점 비교·목표 대비 판독이 둘 다 불가능했다.
 * 표본 3건 미만이면 막대 대신 캡션만 남긴다(단일 막대는 렌더 아티팩트로 읽힌다).
 * PASS 무채·FAIL danger + 상단 ■ 마커(색 단독 아님). 수치 판독은 아래 표가 주 채널이라
 * 요약 aria-label만 낭독한다.
 */

import type { RecoveryRun } from "@/lib/admin/types";
import styles from "./admin.module.css";

const MAX_RUNS = 20;
const MIN_RUNS = 3;
const BAR_W = 6;
const BAR_GAP = 4;
const H = 40;
// FAIL ■ 마커가 최고 막대 위에 겹치지 않게 상단 존을 예약한다
const TOP_RESERVE = 8;
/**
 * 스케일 창 단계. 고정 기준선을 두지 않는다.
 *
 * 재생 소요는 저널 길이에 따라 자릿수가 달라진다. 실측(2026-08-30, n=34)은 min 40 · p50 73 ·
 * max 159ms인데 여기에 초 단위 기준선을 그으면 전 막대가 최소 높이 스텁이 되어 시점 비교가
 * 죽는다. 종전 30초선이 그랬고 5초선도 같다(159ms는 5초의 3%). 반대로 대역이 커지면 자기
 * 최댓값 정규화는 최고 막대가 항상 천장에 닿아 목표 대비 판독이 사라진다.
 *
 * 그래서 표본 최댓값을 덮는 최소 단계를 골라 창을 고정한다. 같은 단계 안에서는 주행 간
 * 비교가 성립하고, 단계가 바뀌면 축이 바뀐 것을 svg title이 밝힌다. 창을 노출하지 않으면
 * 201ms 한 건이 섞이는 순간 전 막대가 0.4배로 줄어드는데(창 200에서 500) 보는 쪽은 이유를 모른다.
 * 모니터링 계기의 창 규약과 같은 방식이다(METER_WINDOW_STEPS).
 */
const SCALE_STEPS_MS = [200, 500, 1_000, 5_000, 30_000, 120_000];

/** 자릿수가 달라지므로 초 고정 표기를 쓰지 않는다. 1초 미만은 ms로 읽는다. */
function fmt(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}초`;
}

export default function RtoSpark({ runs }: { runs: RecoveryRun[] }) {
  if (runs.length === 0) return null;
  if (runs.length < MIN_RUNS) {
    return <span className={styles.rtoFew}>이력 {runs.length}건 · 표본 부족</span>;
  }
  // 원본 정렬 보증이 없어 시간순 사본으로 그린다
  const recent = [...runs]
    .sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime())
    .slice(-MAX_RUNS);
  const durations = recent.map((r) => r.durationMs);
  const maxMs = Math.max(...durations);
  // 표본을 덮는 최소 단계. 마지막 단계보다 크면 자기 최댓값에 여유를 준다.
  const scaleMs = SCALE_STEPS_MS.find((step) => maxMs <= step) ?? maxMs * 1.2;
  const minMs = Math.min(...durations);
  const failCount = recent.filter((r) => r.verdict === "FAIL").length;
  const width = recent.length * BAR_W + (recent.length - 1) * BAR_GAP;
  const drawH = H - 1 - TOP_RESERVE;
  const label =
    `최근 ${recent.length}회 재생, 최소 ${fmt(minMs)} · 최대 ${fmt(maxMs)}, ` +
    `창 ${fmt(scaleMs)}, 불일치 ${failCount}회`;

  return (
    <svg
      className={styles.rtoSpark}
      width={width}
      height={H}
      viewBox={`0 0 ${width} ${H}`}
      role="img"
      aria-label={label}
    >
      <title>{`창 ${fmt(scaleMs)} 기준`}</title>
      <line className={styles.rtoBase} x1={0} x2={width} y1={H - 0.5} y2={H - 0.5} />
      {recent.map((r, i) => {
        const h = Math.max(2, (r.durationMs / scaleMs) * drawH);
        const x = i * (BAR_W + BAR_GAP);
        const y = H - 1 - h;
        const fail = r.verdict === "FAIL";
        return (
          <g key={r.id}>
            <rect className={styles.rtoBar} data-fail={fail ? "" : undefined} x={x} y={y} width={BAR_W} height={h} />
            {fail && <rect className={styles.rtoFailMark} x={x + BAR_W / 2 - 2} y={y - 6} width={4} height={4} />}
          </g>
        );
      })}
    </svg>
  );
}
