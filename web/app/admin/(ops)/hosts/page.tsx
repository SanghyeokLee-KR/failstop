"use client";

/**
 * 서버·노드(/admin/hosts, 관리자 설계 4-2). 호스트 네 대와 조회 노드 ASG의 지금 값이다. 게이지 눈금은
 * 임계값 관리의 현재값이고 추이는 Grafana로 보낸다. 로컬 조정기의 희망 대수 조작은 용량·부하 화면에 있다.
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import Skeleton from "@/components/Skeleton";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { alertRefMap, AsgBand, HostCard, hostTone, ROLE_WORD } from "@/components/admin/control/Infra";
import OpsHead from "@/components/admin/control/OpsHead";
import { ago, PanelHead, SectionBody, Shape, useClock } from "@/components/admin/control/parts";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";

export default function HostsPage() {
  const { data, loading, refresh } = useOpsCenter();
  const now = useClock();
  const reg = data?.thresholds.state === "READY" ? data.thresholds.data : null;
  const alertRefs = alertRefMap(data);

  return (
    <div className={adminStyles.page}>
      <OpsHead
        title="서버·노드"
        desc="호스트와 조회 노드의 지금 값. 게이지 눈금은 임계값 관리의 현재값"
        center={data}
        now={now}
        actions={<GrafanaLink board="msp-infra" label="03 AWS·서버 추이" />}
      />
      {data === null ? (
        loading ? <Skeleton variant="card" width="100%" height={420} /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <section className={styles.panel} aria-labelledby="hosts-cards">
            <PanelHead id="hosts-cards" title="호스트" meta="app · DB 두 대 · bot-1(시장 봇과 관측)" />
            <SectionBody section={data.hosts} what="호스트 지표">
              {(hosts) => (
                <div className={styles.hostGrid} data-cols="4">
                  {hosts.map((h) => (
                    <HostCard key={h.id} host={h} reg={reg} now={now} alertRefs={alertRefs} />
                  ))}
                </div>
              )}
            </SectionBody>
          </section>
          <div className={styles.split}>
            <section className={styles.panel} aria-labelledby="hosts-asg">
              <PanelHead
                id="hosts-asg"
                title="조회 노드 ASG"
                meta="ADR 0030 기준 최소 2 · 최대 3"
                actions={<Link href="/admin/capacity" className={styles.stripLink}>용량·부하(로컬 조정기)</Link>}
              />
              <SectionBody section={data.readTier} what="조회 노드 ASG">
                {(tier) => <AsgBand tier={tier} reg={reg} alertRefs={alertRefs} />}
              </SectionBody>
              <p className={styles.lead}>
                대수 증감은 AWS 지표 정책이 기본으로 맡는다. AI는 자동 모드에서 최소~최대 안의 ±1만 하고, 최대를 넘는 증설은 비용이 드는 자원 생성이라 금지 목록이다.
                매칭 리더와 DB는 이 그룹에 들지 않는다.
              </p>
            </section>
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
                        <th>수집</th>
                        <th data-num="">마지막 수집</th>
                      </tr>
                    </thead>
                    <tbody>
                      {hosts.map((h) => {
                        const tone = hostTone(h, reg);
                        return (
                          <tr key={h.id}>
                            <td>
                              <Shape tone={tone === "danger" ? "danger" : tone === "warning" ? "warning" : "success"}>
                                {tone === "danger" ? "■" : tone === "warning" ? "▲" : "●"}
                              </Shape>{" "}
                              <b>{h.id}</b>
                            </td>
                            <td>{ROLE_WORD[h.role]}</td>
                            <td>
                              {h.instanceType} · {h.az}
                            </td>
                            <td>{h.collect === "OK" ? "정상" : h.collect === "STALE" ? "▲ 지연" : "◌ 미수집"}</td>
                            <td data-num="" className="num">
                              {h.collectedAt ? ago(h.collectedAt, now) : "없음"}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </SectionBody>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
