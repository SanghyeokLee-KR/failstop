"use client";

/**
 * HA 노드 토폴로지(모니터링 노드 상태). 리더 좌·팔로워 우 배치로 복제 방향을 공간으로 전달한다.
 * 리더 표식은 좌측 레일 + "리더" 평문 라벨 병기(색 단독 아님). 헬스 pill은 전이 플래시 옵트인.
 */

import type { HaNode, NodeHealth } from "@/lib/admin/types";
import { formatKRW } from "@/lib/format";
import { HealthPill, type Health } from "./ui";
import { MeterRail } from "./instruments/MeterRail";
import styles from "./admin.module.css";

// 팔로워 lag 판독 보조 상수. 서버 계약에 임계가 없어 프론트 판독 기준으로만 쓴다
const LAG_MAX_MS = 2000;
const LAG_WARN_MS = 1000;

const NODE_HEALTH: Record<NodeHealth, Health> = { healthy: "success", degraded: "warning", down: "danger" };
const NODE_HEALTH_LABEL: Record<NodeHealth, string> = { healthy: "정상", degraded: "성능 저하", down: "중단" };

function NodeCard({ node }: { node: HaNode }) {
  const isLeader = node.role === "leader";
  const lagWarn = node.followerLagMs > LAG_WARN_MS;
  return (
    <div className={styles.nodeCard} data-leader={isLeader || undefined}>
      <div className={styles.nodeHead}>
        <span className={styles.nodeId}>{node.id}</span>
        <span className={styles.nodeRole}>{isLeader ? "리더" : "팔로워"}</span>
        <HealthPill health={NODE_HEALTH[node.health]} label={NODE_HEALTH_LABEL[node.health]} flashOnChange />
      </div>
      {/* 1차 표기는 한국어 평이어, 원어(epoch·seq)는 title 채널로 강등한다(§4-2 표시층) */}
      <div className={`${styles.nodeSeq} num`} title={`epoch ${node.epoch} · seq ${node.seq}`}>
        <span>
          선출 세대 <b>{node.epoch}</b>
        </span>
        <span>
          처리 순번 <b>{formatKRW(node.seq)}</b>
        </span>
      </div>
      {!isLeader && (
        <div className={styles.nodeLag} title="follower lag · 낮을수록 좋음">
          <div className={styles.nodeLagHead}>
            <span className={styles.nodeLagLabel}>복제 지연</span>
            <span className={`${styles.nodeLagValue} num`} data-warn={lagWarn || undefined}>
              {lagWarn && <span aria-hidden="true">▲ </span>}
              {lagWarn && <span className="sr-only">지연 임계 초과 </span>}
              {node.followerLagMs}ms
            </span>
          </div>
          {/* 위 라벨·수치 텍스트가 낭독을 전담하므로 레일은 aria-hidden */}
          <MeterRail
            value={node.followerLagMs}
            max={LAG_MAX_MS}
            threshold={LAG_WARN_MS}
            tone={lagWarn ? "warning" : undefined}
          />
        </div>
      )}
      {/* 다마켓 나열은 셀 폭을 넘쳐 이웃과 겹친다. 1행 축약 + title로 전수 보존, 카드 높이도 마켓 수와 무관해진다 */}
      {node.ownedMarkets.length === 0 ? (
        <span className={styles.nodeMarkets}>소유 마켓 없음</span>
      ) : (
        <span className={styles.nodeMarkets} title={node.ownedMarkets.join(" · ")}>
          {node.ownedMarkets.length >= 3
            ? `${node.ownedMarkets[0]} 외 ${node.ownedMarkets.length - 1}개 마켓`
            : node.ownedMarkets.join(" · ")}
        </span>
      )}
    </div>
  );
}

export default function NodeTopologyRail({ nodes }: { nodes: HaNode[] }) {
  const leaders = nodes.filter((n) => n.role === "leader");
  const followers = nodes.filter((n) => n.role !== "leader");
  return (
    // role 없는 div의 aria-label은 보조기술이 무시하므로 group으로 노출한다
    <div className={styles.topology} role="group" aria-label="HA 노드 토폴로지">
      <div className={styles.topologyLeaders}>
        {leaders.length > 0 ? (
          leaders.map((n) => <NodeCard key={n.id} node={n} />)
        ) : (
          <span className={styles.nodeColEmpty}>리더 없음</span>
        )}
      </div>
      <span className={`rule-rail ${styles.topologySeam}`} data-axis="y" aria-hidden="true" />
      <div className={styles.topologyFollowers}>
        {followers.length > 0 ? (
          followers.map((n) => <NodeCard key={n.id} node={n} />)
        ) : (
          <span className={styles.nodeColEmpty}>팔로워 없음</span>
        )}
      </div>
    </div>
  );
}
