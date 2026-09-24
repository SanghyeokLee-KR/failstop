"use client";

/**
 * 서버·노드(/admin/hosts, 관리자 설계 4-2). 관제센터 인프라 칸과 같은 역할 묶음(앱, DB, 조회 ASG, 관측,
 * 온프레미스)을 넓게 펴고 수집 대상 표를 둔다. 게이지 눈금은 임계값 관리의 현재값이고 추이는 Grafana로
 * 보낸다. 로컬 조정기의 희망 대수 조작은 용량·부하 화면에 있다.
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { hostState } from "@/lib/admin/opsHealth";
import { HOST_KIND, InfraGroups } from "@/components/admin/control/Infra";
import OpsHead from "@/components/admin/control/OpsHead";
import { Ago, PanelHead, SectionBody, Shape } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

export default function HostsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const reg = data?.thresholds.state === "READY" ? data.thresholds.data : null;

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="서버·노드"
        desc="역할별 노드의 지금 값. 게이지 눈금은 임계값 관리의 현재값"
        center={data}
        actions={<GrafanaLink board="msp-infra" label="03 AWS·서버 추이" />}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={420} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <section className={styles.panel} aria-labelledby="hosts-groups">
            <PanelHead id="hosts-groups" title="역할별 노드" meta="앱 두 대 · DB 두 대 · 조회 ASG · bot-1 · 온프레미스 ai-1" />
            <InfraGroups center={data} board wide />
          </section>
          <div className={styles.split} data-ratio="wide">
            <section className={styles.panel} aria-labelledby="hosts-collect">
              <PanelHead id="hosts-collect" title="수집 대상" meta="미수집은 0이 아니라 값이 없는 상태" />
              <SectionBody section={data.hosts} what="수집 상태">
                {(hosts) => (
                  <table className={styles.hostTable}>
                    <thead>
                      <tr>
                        <th>호스트</th>
                        <th>역할</th>
                        <th>규격 · AZ</th>
                        <th>상태</th>
                        <th data-num="">마지막 수집</th>
                      </tr>
                    </thead>
                    <tbody>
                      {hosts.map((h) => {
                        const s = hostState(h, reg);
                        return (
                          <tr key={h.id}>
                            <td>
                              <b>{h.id}</b>
                            </td>
                            <td>{HOST_KIND[h.role]}</td>
                            <td>
                              {h.instanceType} · {h.az}
                            </td>
                            <td>
                              <Shape tone={s.tone}>{s.shape}</Shape> {s.word}
                            </td>
                            <td data-num="" className="num">
                              {h.collectedAt ? <Ago at={h.collectedAt} /> : "없음"}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </SectionBody>
            </section>
            <section className={styles.panel} aria-labelledby="hosts-asg">
              <PanelHead
                id="hosts-asg"
                title="조회 노드 ASG"
                meta="ADR 0030 기준 최소 2 · 최대 3"
                actions={<Link href="/admin/capacity" className={styles.stripLink}>용량·부하(로컬 조정기)</Link>}
              />
              <p className={styles.lead}>
                대수 증감은 AWS 지표 정책이 기본으로 맡습니다. AI는 자동 모드에서 최소~최대 안의 ±1만 하고, 최대를 넘는 증설은 비용이 드는 자원 생성이라 금지 목록입니다.
                매칭 리더(app-1·app-2)와 DB는 이 그룹에 들지 않습니다.
              </p>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
