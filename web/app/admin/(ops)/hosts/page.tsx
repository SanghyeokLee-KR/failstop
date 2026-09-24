"use client";

/**
 * 서버·노드(/admin/hosts, 관리자 설계 4-2). 관제센터 인프라 칸과 같은 역할 묶음(앱, DB, 조회 ASG, 관측,
 * 온프레미스)을 넓게 펴고 수집 대상 표를 둔다. 수집 대상은 EC2 노드와 조회 노드이고 설정 화면의 Prometheus
 * 행과 같은 함수(collectTargets)로 센다. 게이지 눈금은 임계값 관리의 현재값이고 추이는 Grafana로 보낸다.
 * 로컬 조정기의 희망 대수 조작은 용량·부하 화면에 있다.
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { collectTargets, hostState, type CollectRow, type NodeState } from "@/lib/admin/opsHealth";
import { HOST_KIND, InfraGroups } from "@/components/admin/control/Infra";
import OpsHead from "@/components/admin/control/OpsHead";
import { Ago, PanelHead, SectionBody, Shape } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

const READ_STATE: Record<Exclude<CollectRow["readState"], null>, NodeState> = {
  IN_SERVICE: { tone: "success", shape: "●", word: "운영 중" },
  PENDING: { tone: "warning", shape: "▲", word: "기동 중" },
  DRAINING: { tone: "warning", shape: "▲", word: "드레이닝" },
  OFF: { tone: "off", shape: "○", word: "중지 · 수집 대상 아님" },
};

export default function HostsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const reg = data?.thresholds.state === "READY" ? data.thresholds.data : null;

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="서버·노드"
        desc="역할별 노드의 현재값입니다. 게이지 눈금은 임계값 관리의 현재값입니다"
        center={data}
        actions={<GrafanaLink board="msp-infra" label="03 AWS·서버 추이" />}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={420} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <section className={styles.panel} aria-labelledby="hosts-groups">
            <PanelHead id="hosts-groups" title="역할별 노드" meta="앱 두 대 · DB 두 대 · 조회 ASG · bot-1 · 온프레미스 ai-1(구축 전)" />
            <InfraGroups center={data} board wide />
          </section>
          <div className={styles.split} data-ratio="wide">
            <section className={styles.panel} aria-labelledby="hosts-collect">
              <PanelHead
                id="hosts-collect"
                title="수집 대상"
                meta={
                  data.hosts.state === "READY" ? (
                    <span className="num">
                      수집 대상 {collectTargets(data).targets} · 응답 {collectTargets(data).ok}/{collectTargets(data).targets}
                    </span>
                  ) : undefined
                }
              />
              <SectionBody section={data.hosts} what="수집 상태">
                {() => (
                  <table className={styles.hostTable}>
                    <thead>
                      <tr>
                        <th>노드</th>
                        <th>역할</th>
                        <th>규격 · AZ</th>
                        <th>상태</th>
                        <th data-num="">마지막 수집</th>
                      </tr>
                    </thead>
                    <tbody>
                      {collectTargets(data).rows.map((r) => {
                        const s = r.host ? hostState(r.host, reg) : READ_STATE[r.readState ?? "OFF"];
                        return (
                          <tr key={r.id}>
                            <td>
                              <b>{r.id}</b>
                            </td>
                            <td>{r.host ? HOST_KIND[r.host.role] : "Spring Boot · 조회 전용 · ASG"}</td>
                            {/* 규격과 AZ는 각각 한 덩이다. 좁은 칸에서 'ap-'에서 끊기지 않게 가운뎃점 뒤에서만 줄을 넘긴다(§12-12) */}
                            <td>
                              <span className={styles.nowrap}>{r.instanceType}</span>
                              {"\u00a0· "}
                              <span className={styles.nowrap}>{r.az}</span>
                            </td>
                            <td>
                              <Shape tone={s.tone}>{s.shape}</Shape> {s.word}
                            </td>
                            <td data-num="" className="num">
                              {r.collectedAt ? <Ago at={r.collectedAt} /> : "없음"}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </SectionBody>
              <p className={styles.lead}>미수집은 0이 아니라 값을 받지 못한 상태입니다. 구축 전인 ai-1은 수집 대상에 넣지 않습니다.</p>
            </section>
            <section className={styles.panel} aria-labelledby="hosts-asg">
              <PanelHead
                id="hosts-asg"
                title="조회 노드 ASG"
                meta="최소 2 · 최대 3"
                actions={<Link href="/admin/capacity" className={styles.stripLink}>용량·부하(로컬 조정기)</Link>}
              />
              <p className={styles.lead}>
                대수는 ADR 0030에 따라 최소 2대, 최대 3대이고 증감은 AWS 지표 정책이 기본으로 맡습니다. AI는 자동 모드에서 최소~최대 안의 ±1만 하고, 최대를 넘는 증설은 비용이 드는 자원 생성이라 금지 목록입니다.
                매칭 리더(app-1·app-2)와 DB는 이 그룹에 들지 않습니다.
              </p>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
