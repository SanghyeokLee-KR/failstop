"use client";

/**
 * 노드 판독 스트립. 한 대가 한 행이다.
 *
 * 종전에는 같은 노드를 세 화면이 각자 그렸다. 홈은 리스에서 온 합성 노드를 링크 행으로,
 * 모니터링은 토폴로지 레일로, 용량은 dl 두 벌을 쌓은 카드로 그렸다. 셋은 원천도 필드도
 * 달라 화면마다 다른 결론을 냈다. 실제 관측이 있는 것은 용량의 NodeProbe 하나뿐이므로
 * 그 원천만 남기고 표현을 이 컴포넌트로 모았다.
 *
 * 카드가 아니라 행인 이유. 세 대를 카드로 세우면 같은 라벨이 세 번 반복되고 세로로
 * 길어져 한눈에 비교가 안 된다. 수치를 같은 칼럼에 세로로 정렬해야 대수 비교가 된다.
 *
 * 응답하지 않는 서버는 관측 칸을 비운다. 마지막으로 본 값을 남기면 죽은 서버가 살아 있는
 * 것처럼 보인다.
 */

import type { CapacityNode } from "@/lib/admin/types";
import { MeterRail } from "./instruments/MeterRail";
import { HealthPill, type Health } from "./ui";
import styles from "./admin.module.css";

function heapRatio(n: CapacityNode): number | null {
  if (n.heapUsedBytes === null || n.heapMaxBytes === null || n.heapMaxBytes <= 0) return null;
  return n.heapUsedBytes / n.heapMaxBytes;
}

/** 받는 일. 로컬 단일 리더는 주문과 조회를 함께 받고, 마켓별 리더 배치의 매칭 노드는 주문만 받는다 */
function dutyOf(n: CapacityNode): string {
  if (n.leader) return n.servesReads ? "주문+조회" : "주문";
  return "조회";
}

function stateOf(n: CapacityNode): { health: Health; label: string; off?: true } {
  // 기동과 내림은 장애가 아니라 대수를 맞추는 중이다. 응답 여부보다 먼저 읽어야 기동 중인 노드가 '응답 없음'으로 보이지 않는다
  if (n.lifecycle === "PENDING") return { health: "warning", label: "기동 중" };
  if (n.lifecycle === "DRAINING") return { health: "warning", label: "내리는 중" };
  if (!n.reachable) {
    // 계약상 떠 있어야 하는데 응답이 없으면 장애다. 계약에 없으면 꺼져 있는 것이 정상이다.
    // 중지는 판정이 아니라 운영자·정책이 내린 상태라 초록 점이 아니라 무채 ○로 적는다(§12-3)
    return n.expected ? { health: "danger", label: "응답 없음" } : { health: "success", label: "중지", off: true };
  }
  if (n.cpuRatio !== null && n.cpuRatio >= 0.9) return { health: "warning", label: dutyOf(n) };
  return { health: "success", label: dutyOf(n) };
}

function pct(v: number | null): string {
  return v === null ? "미수집" : `${Math.round(v * 100)}%`;
}

function count(v: number | null, unit: string): string {
  return v === null ? "미수집" : `${Math.round(v).toLocaleString("ko-KR")}${unit}`;
}

/**
 * 초당 요청 칸. 값이 없는 사유가 둘이라 말을 가른다. 서버가 요청 누적값을 비워 보냈으면
 * 지표를 받지 못한 것이라 다른 칸과 같은 "미수집"이다. 누적값은 왔는데 비율이 없으면
 * 첫 표본이거나 재기동으로 값이 줄었거나 간격이 1초 미만이라 차분을 낼 표본이 모자란 것이다.
 */
function requestRate(n: CapacityNode, rps: number | null): string {
  if (n.httpRequests === null) return "미수집";
  return rps === null ? "표본 부족" : count(rps, "건");
}

/**
 * 미수집은 값을 받지 못했다는 사실이라 무채 ◌ 형태를 앞에 둔다(§12-3). 형태는 낭독하지 않는다.
 * 값 칸이 세로 flex라 형태와 글자를 한 span으로 묶어야 두 줄로 갈라지지 않는다
 */
function Reading({ text }: { text: string }) {
  if (text !== "미수집") return <>{text}</>;
  return (
    <span>
      <span aria-hidden="true">◌ </span>
      {text}
    </span>
  );
}

export default function NodeStrip({
  nodes,
  perSecond,
  cpuMark,
}: {
  nodes: CapacityNode[];
  /** 누적 카운터 차분으로 만든 초당 요청. 누적값이 없거나 차분을 낼 표본이 모자라면 null */
  perSecond: (n: CapacityNode) => number | null;
  /** 사용률 경보선. 서버 응답의 증설 임계를 그대로 받는다. 판정은 서버 advice가 내리고 선은 판독 보조다 */
  cpuMark: number;
}) {
  return (
    <div className={styles.nodeStrip}>
      <div className={styles.nodeStripHead} aria-hidden="true">
        <span>노드</span>
        <span>구성</span>
        <span>사용률</span>
        <span title="호스트 메모리가 아니라 앱의 JVM 힙 사용률입니다">JVM 힙</span>
        <span>초당 요청</span>
        <span>동시 접속</span>
      </div>
      {nodes.map((n) => {
        const st = stateOf(n);
        const heap = heapRatio(n);
        const rps = perSecond(n);
        return (
          <div key={n.id} className={styles.nodeStripRow} data-leader={n.leader || undefined} data-health={st.health}>
            <span className={styles.nodeStripId}>
              <strong>{n.id}</strong>
              {st.off ? (
                <span className={styles.healthInk} data-off="">
                  <span aria-hidden="true">○</span> {st.label}
                </span>
              ) : (
                <HealthPill health={st.health} label={st.label} />
              )}
            </span>
            <span className={styles.nodeStripMeta}>
              {n.az || "구역 미지정"} · {n.instanceType || "사양 미지정"}
              {!n.scalable && <span className={styles.capacityTag}>증설 불가</span>}
              {!n.expected && <span className={styles.capacityTag}>계약 밖</span>}
            </span>
            {n.reachable ? (
              <>
                <span className={`${styles.nodeStripValue} num`}>
                  <Reading text={pct(n.cpuRatio)} />
                  {n.cpuRatio !== null && (
                    // 세로 flex의 flex-end 안에서 레일은 고유 폭이 없어 0으로 무너진다.
                    // 폭을 소유하는 래퍼를 둬 칼럼 폭을 그대로 물려준다
                    // 증설 임계는 조회 노드 평균에 거는 선이라 늘릴 수 없는 노드에는 긋지 않는다
                    <span className={styles.nodeStripRail}>
                      <MeterRail
                        value={n.cpuRatio}
                        max={1}
                        threshold={n.scalable ? cpuMark : undefined}
                        tone={n.scalable && n.cpuRatio >= cpuMark ? "warning" : undefined}
                      />
                    </span>
                  )}
                </span>
                <span className={`${styles.nodeStripValue} num`}>
                  <Reading text={pct(heap)} />
                </span>
                <span className={`${styles.nodeStripValue} num`}>
                  <Reading text={requestRate(n, rps)} />
                </span>
                <span className={`${styles.nodeStripValue} num`}>
                  <Reading text={count(n.wsClients, "명")} />
                </span>
              </>
            ) : (
              // 관측 네 칸을 한 칸으로 합쳐 비운다. 칸마다 "미수집"을 네 번 적으면
              // 읽는 쪽이 네 개의 실패로 읽는다. 실패는 하나다. 서버가 대답하지 않는 것이다.
              <span className={styles.nodeStripNote}>
                {n.lifecycle === "PENDING" ? "준비되면 값을 읽습니다" : n.expected ? "상태 읽기 불가" : "현재 대수에서 미사용"}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
