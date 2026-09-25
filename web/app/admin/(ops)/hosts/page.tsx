"use client";

/**
 * 서버·노드(/admin/hosts, 관리자 설계 4-10-3). 어떤 노드가 어떤 구성과 상태로 돌고 있는지에 답한다. 위에서부터 역할 묶음
 * 카드(앱 계층, DB, 관측, 온프레미스), 자산 목록(호스트, 역할, 위치, 타입, OS·커널, 가동 시간, 보안 업데이트, 수집기,
 * 디스크·inode, 실패한 서비스, 최근 재부팅), 앱 계층 자동 증설의 규칙이다. 자산 행과 카드 이름은 노드 상세를 연다.
 *
 * 수집 대상은 EC2 노드 다섯 대와 켜진 app-3이고 설정 화면의 Prometheus 행과 같은 함수(collectTargets)로 센다. 게이지 눈금은
 * 임계값 관리의 현재값이고 추이는 Grafana로 보낸다. app-3 켜기·끄기(앱 계층 희망 대수)는 용량·부하 화면에 있다.
 */

import Link from "next/link";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import { collectTargets } from "@/lib/admin/opsHealth";
import GrafanaLink from "@/components/admin/GrafanaLink";
import { AdminErrorState } from "@/components/admin/ResourceState";
import { InfraGroups } from "@/components/admin/control/Infra";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import { PanelHead, SectionBody } from "@/components/admin/control/parts";
import AssetTable from "@/components/admin/hosts/AssetTable";
import KeepNames from "@/components/admin/KeepNames";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import hostStyles from "@/components/admin/hosts/hosts.module.css";

export default function HostsPage() {
  const { data, loading, refresh } = useOpsCenter();

  return (
    <div className={adminStyles.page}>
      <OpsHead title="서버·노드" center={data} actions={<GrafanaLink board="msp-infra" label="03 AWS·서버 추이" />} />
      {data === null ? (
        loading ? <PageSkeleton columns={[1]} rows={[6]} label="서버·노드 판을 불러오는 중입니다" /> : <AdminErrorState onRetry={() => void refresh()} />
      ) : (
        <>
          <section className={styles.panel} aria-labelledby="hosts-groups">
            <PanelHead id="hosts-groups" title="역할별 노드" meta="앱 계층 2~3대 · DB 두 대 · bot-1 · 온프레미스 ai-1(구축 전)" />
            <InfraGroups center={data} board wide />
            <p className={hostStyles.guide}>
              <span>게이지 눈금은 임계값 관리의 현재값입니다</span>
              <span>노드 이름을 누르면 상세가 열립니다</span>
              <span>추이는 Grafana 03 AWS·서버에서 봅니다</span>
            </p>
          </section>

          <section className={styles.panel} aria-labelledby="hosts-assets">
            <PanelHead
              id="hosts-assets"
              title="자산 목록"
              meta={
                data.hosts.state === "READY" ? (
                  <span className="num">
                    수집 대상 {collectTargets(data).targets} · 응답 {collectTargets(data).ok}/{collectTargets(data).targets}
                  </span>
                ) : undefined
              }
            />
            <SectionBody section={data.hosts} what="호스트 자산">
              {() => <AssetTable center={data} />}
            </SectionBody>
          </section>
          <p className={hostStyles.guide}>
            <span>미수집은 0이 아니라 값을 받지 못한 상태입니다</span>
            <span>구축 전인 ai-1은 수집 대상에 넣지 않습니다</span>
            <span>실행 단추는 없고, 재시작과 정리는 런북을 따라 운영자가 SSM 세션에서 합니다</span>
          </p>

          <section className={styles.panel} aria-labelledby="hosts-asg">
            <PanelHead
              id="hosts-asg"
              title="앱 계층 자동 증설"
              meta="앱 2~3대 · Auto Scaling은 app-3만"
              actions={
                <Link href="/admin/capacity" className={styles.stripLink}>
                  용량·부하에서 켜고 끄기
                </Link>
              }
            />
            <div className={hostStyles.asgFacts}>
              <div className={hostStyles.asgFact}>
                <span className={hostStyles.asgFactTitle}>고정 두 대 · app-1·app-2</span>
                <span><KeepNames text="마켓을 나눠 맡는 매칭 리더이면서 조회도 받습니다. AWS가 임의로 종료하거나 교체하지 않게 Auto Scaling 그룹에 넣지 않습니다(ADR 0030)." /></span>
              </div>
              <div className={hostStyles.asgFact}>
                <span className={hostStyles.asgFactTitle}>조회 전용 · app-3 0~1대</span>
                <span><KeepNames text="부하가 오르면 Auto Scaling으로 켜져 조회가 세 노드로 나뉘고, 부하가 내려가면 꺼집니다. 켤 때마다 새 인스턴스가 뜹니다." /></span>
              </div>
              <div className={hostStyles.asgFact}>
                <span className={hostStyles.asgFactTitle}>켜고 끄는 주체</span>
                <span><KeepNames text="AWS 지표 정책이 기본으로 맡고 AI는 자동 모드에서 app-3을 켜고 끄는 일만 합니다. 3대를 넘는 증설은 비용이 드는 자원 생성이라 금지 목록입니다." /></span>
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
