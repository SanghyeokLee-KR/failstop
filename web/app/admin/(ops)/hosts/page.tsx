"use client";

/**
 * 서버·노드(/admin/hosts, 관리자 설계 4-2). 관제센터 인프라 칸과 같은 역할 묶음(앱 계층, DB, 관측, 온프레미스)을
 * 넓게 펴고 수집 대상 표를 둔다. 수집 대상은 EC2 노드 다섯 대와 켜진 app-3이고 설정 화면의 Prometheus 행과 같은
 * 함수(collectTargets)로 센다. 게이지 눈금은 임계값 관리의 현재값이고 추이는 Grafana로 보낸다. app-3 켜기·끄기(앱 계층
 * 희망 대수)는 용량·부하 화면에 있다.
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { appTierHot, collectTargets, hostState, type CollectRow, type NodeState } from "@/lib/admin/opsHealth";
import { HOST_KIND, InfraGroups, NodeName } from "@/components/admin/control/Infra";
import OpsHead from "@/components/admin/control/OpsHead";
import { Ago, PanelHead, SectionBody, Shape } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

const READ_STATE: Record<Exclude<CollectRow["readState"], null>, NodeState> = {
  IN_SERVICE: { tone: "success", shape: "●", word: "운영 중" },
  PENDING: { tone: "warning", shape: "▲", word: "기동 중" },
  DRAINING: { tone: "warning", shape: "▲", word: "드레이닝" },
  OFF: { tone: "off", shape: "○", word: "꺼짐 · 수집 대상 아님" },
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
            <PanelHead id="hosts-groups" title="역할별 노드" meta="앱 계층 2~3대 · DB 두 대 · bot-1 · 온프레미스 ai-1(구축 전)" />
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
                        const s = r.host ? hostState(r.host, reg, appTierHot(data)) : READ_STATE[r.readState ?? "OFF"];
                        return (
                          <tr key={r.id}>
                            <td>
                              <NodeName id={r.id} />
                            </td>
                            <td>{r.host ? HOST_KIND[r.host.role] : "Spring Boot · 조회 전용 · Auto Scaling"}</td>
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
                title="앱 계층 자동 증설"
                meta="앱 2~3대 · Auto Scaling은 app-3만"
                actions={<Link href="/admin/capacity" className={styles.stripLink}>용량·부하(로컬 조정기)</Link>}
              />
              <p className={styles.lead}>
                앱 계층은 ADR 0030에 따라 최소 2대, 최대 3대입니다. app-1·app-2는 마켓을 나눠 맡는 매칭 리더이면서 조회도 받는 고정 노드이고, AWS가 임의로 종료하거나 교체하지 않게 Auto Scaling 그룹에 넣지 않습니다.
                부하가 오르면 조회 전용 app-3가 Auto Scaling으로 켜져 조회가 세 노드로 나뉘고, 부하가 내려가면 꺼집니다. 켜고 끄기는 AWS 지표 정책이 기본으로 맡고 AI는 자동 모드에서 app-3 켜기·끄기만 합니다. 3대를 넘는 증설은 비용이 드는 자원 생성이라 금지 목록입니다.
              </p>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
