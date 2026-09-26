"use client";

/**
 * 네트워크·보안(/admin/security, 관리자 설계 4-10-3). 어디로 들어올 수 있고, 무엇이 곧 만료되며, 누가 관리 권한을 썼는지에
 * 답한다. 위는 전폭 허용 경로 표, 그 아래 두 칸 1 : 1(인증서 | 관리 접근 기록), 그 아래 전폭 공개 포트 점검과 경계 방화벽
 * 안내 한 줄이다.
 *
 * 허용 경로 표는 설계 의도다. Terraform 예제의 배치(개인 계정 목표 구성)와 학교 계정에 콘솔로 적용한 기록을 옮긴 정적 표이고
 * 콘솔이 AWS에서 읽은 값이 아니다('설계 의도 · 관측 아님'). 인증서는 sim이 관제 판의 인증서 칸(학교 발급 기록의 만료일)이고
 * 관제센터 운영 작업 줄, 사이드바 배지와 같은 판정(certTone)이다. http는 서버의 인증서 원천이 연결 전이다. 관리 접근 기록은
 * 조치 기록과 같은 감사 조회에서 세션·승인과 권한 거부만 거른다. 공개 포트 점검은 외부 프로브가 없어 두 원천 모두 연결 전이다.
 */

import Link from "next/link";
import { useMemo, useState } from "react";
import { isAdminHttp } from "@/lib/admin/api";
import { CERT_SOURCE, certDateWord, certDaysLeft, certTone } from "@/lib/admin/certs";
import { ALLOWED_PATHS, BOUNDARY_NOTE, BOUNDARY_SOURCE, PORT_SCAN_SOURCE, SCHOOL_SG_SOURCE } from "@/lib/admin/netPaths";
import { useOpsCenter } from "@/lib/admin/opsCenter";
import type { ActionRecord, CertificateInfo } from "@/lib/admin/types";
import ActionDetail from "@/components/admin/actions/ActionDetail";
import ActionTable from "@/components/admin/actions/ActionTable";
import { useActionFeed } from "@/components/admin/actions/useActionFeed";
import { CommandChip } from "@/components/admin/CommandChip";
import KeepNames from "@/components/admin/KeepNames";
import { AdminErrorState } from "@/components/admin/ResourceState";
import StatusShape from "@/components/admin/StatusShape";
import OpsHead from "@/components/admin/control/OpsHead";
import PageSkeleton from "@/components/admin/control/PageSkeleton";
import { NotConnected, PanelHead, SectionGap, Unavailable, useClock } from "@/components/admin/control/parts";
import { SimMark } from "@/components/admin/ui";
import adminStyles from "@/components/admin/admin.module.css";
import styles from "@/components/admin/control/control.module.css";
import secStyles from "@/components/admin/security/security.module.css";

/**
 * 관리 접근 기록에 싣는 줄. 세션 발급과 승인 발급·거부(세션·승인 종류), 승인 사용(승인 티켓을 소각한 조치), 운영자의 권한 거부다
 */
function isAccess(r: ActionRecord): boolean {
  return r.category === "SESSION" || r.approvalId !== null || (r.result === "DENIED" && r.actor === "OPERATOR");
}

/** 만료의 글. 시각까지 기록된 인증서는 KST 시각, UTC 날짜로 기록된 인증서는 그렇게 밝히고, 날짜만 있으면 그렇게 적는다 */
function expiryWord(c: CertificateInfo): string {
  if (c.expiresAt) return `${c.expiresOn} ${certDateWord(c).split(" ")[1]} KST`;
  return c.utcDate ? `${c.expiresOn} UTC` : `${c.expiresOn} · 날짜만 기록`;
}

const ACCESS_ROWS = 12;

/** 운영자가 들어온 경로. 배포마다 하나라 표 아래 캡션에 한 번 적는다. sim은 인증서 목록을 모사하고, http는 배포의 인증 방식이다 */
function accessPath(sim: boolean): string {
  if (sim) return "mTLS 인증서 · 모사";
  return process.env.NEXT_PUBLIC_ADMIN_AUTH === "password" ? "비밀번호 게이트웨이" : "mTLS 인증서";
}

/** 근거 칸의 이름. 경로는 좁은 칸에서 잘리므로 문서 이름으로 적고 원문 경로는 툴팁이 싣는다 */
function sourceName(path: string): string {
  if (path.includes("terraform")) return "Terraform 예제";
  const adr = /adr\/(\d{4})-/.exec(path);
  if (adr) return `ADR ${adr[1]}`;
  return path.split("/").pop() ?? path;
}

function AllowedPaths() {
  return (
    <div className={secStyles.tableWrap}>
      <table className={secStyles.table}>
        <colgroup>
          <col className={secStyles.cNo} />
          <col className={secStyles.cFrom} />
          <col className={secStyles.cTo} />
          <col className={secStyles.cPort} />
          <col />
          <col className={secStyles.cSource} />
          <col className={secStyles.cSchool} />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">
              <span className="sr-only">순번</span>
            </th>
            <th scope="col">출발</th>
            <th scope="col">도착</th>
            <th scope="col">포트</th>
            <th scope="col">목적</th>
            <th scope="col">근거</th>
            <th scope="col">학교 적용 기록</th>
          </tr>
        </thead>
        <tbody>
          {ALLOWED_PATHS.map((p, i) => (
            <tr key={`${p.from}-${p.to}-${p.port}`}>
              <td className={`${secStyles.no} num`}>{i + 1}</td>
              <td>
                <KeepNames text={p.from} />
              </td>
              <td className={secStyles.strong}>
                <KeepNames text={p.to} />
              </td>
              <td className={`${secStyles.port} num`}>{p.port}</td>
              <td>
                <KeepNames text={p.purpose} />
              </td>
              <td>
                <span className={secStyles.source} title={p.source}>
                  {sourceName(p.source)}
                </span>
              </td>
              <td>{p.school ? <KeepNames text={p.school} /> : <span className={secStyles.none}>따로 적은 기록 없음</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Certificates({ certs, now }: { certs: CertificateInfo[]; now: number }) {
  const sorted = [...certs].sort((a, b) => a.expiresOn.localeCompare(b.expiresOn));
  return (
    <ul className={secStyles.certs}>
      {sorted.map((c) => {
        const days = certDaysLeft(c, now);
        const tone = certTone(days);
        return (
          <li key={c.domain} className={secStyles.cert}>
            <div className={secStyles.certHead}>
              <span className={secStyles.certName}>
                <KeepNames text={c.domain} />
              </span>
              <span className={`${secStyles.days} num`} data-tone={tone}>
                <StatusShape kind={tone === "success" ? "circle" : tone === "warning" ? "triangle" : "square"} size={10} />
                {days >= 0 ? `D-${days}` : `만료 ${-days}일 지남`}
              </span>
              {/* 표식 안에 두면 가려진 글의 줄 상자가 머리 줄 밖으로 삐져나가 감김으로 잡힌다. 형제로 둔다 */}
              <span className="sr-only">{tone === "success" ? "여유" : tone === "warning" ? "주의 · 30일 이내" : "장애 · 7일 이내"}</span>
            </div>
            <dl className={secStyles.certKv}>
              <dt>쓰는 곳</dt>
              <dd>
                <KeepNames text={c.purpose} />
              </dd>
              <dt>발급자</dt>
              <dd>{c.issuer}</dd>
              <dt>만료</dt>
              <dd className="num">{expiryWord(c)}</dd>
              <dt>갱신 방식</dt>
              <dd>{c.renewal}</dd>
              <dt>모의 갱신</dt>
              <dd className="num">{c.lastDryRunOn ? `${c.lastDryRunOn} 성공` : c.autoRenew ? "기록 없음" : "해당 없음 · 자동 갱신 없음"}</dd>
            </dl>
          </li>
        );
      })}
    </ul>
  );
}

export default function SecurityPage() {
  const { data, loading, refresh } = useOpsCenter();
  const sim = !isAdminHttp();
  const now = useClock(60_000);
  const signal = sim && data?.incidents.state === "READY" ? data.incidents.data.events[0]?.id ?? null : null;
  const feed = useActionFeed(signal);
  const [open, setOpen] = useState<string | null>(null);
  const access = useMemo(() => (feed.rows ? feed.rows.filter(isAccess) : null), [feed.rows]);
  const path = accessPath(sim);

  if (data === null) {
    return (
      <div className={adminStyles.page}>
        <OpsHead title="네트워크·보안" center={data} />
        {loading ? <PageSkeleton columns={[1]} rows={[4]} label="네트워크·보안 판을 불러오는 중입니다" /> : <AdminErrorState onRetry={() => void refresh()} />}
      </div>
    );
  }
  const jobs = data.jobs.state === "READY" ? data.jobs.data : null;

  return (
    <div className={adminStyles.page}>
      <OpsHead title="네트워크·보안" center={data} />

      <section className={styles.panel} aria-labelledby="sec-paths">
        <PanelHead
          id="sec-paths"
          title="허용 경로"
          meta="개인 계정 목표 구성(Terraform 예제)과 학교 계정 적용 기록"
          actions={<SimMark kind="design" />}
        />
        <AllowedPaths />
        <div className={secStyles.foot}>
          <span>두 열 모두 콘솔이 AWS에서 읽은 값이 아닙니다. 보안 그룹의 실제 규칙(DescribeSecurityGroups)은 읽지 않습니다</span>
          <CommandChip command="deploy/terraform/README.md" kind="path" />
          <CommandChip command={SCHOOL_SG_SOURCE} kind="path" />
        </div>
      </section>

      <div className={styles.split}>
        <section className={styles.panel} aria-labelledby="sec-certs">
          <PanelHead id="sec-certs" title="인증서" meta={jobs?.certificates ? "30일 이내 주의 · 7일 이내 장애 · 사이드바 배지와 같은 판정" : undefined} />
          {data.jobs.state !== "READY" ? (
            <SectionGap section={data.jobs} what="인증서 만료" />
          ) : jobs?.certificates ? (
            <Certificates certs={jobs.certificates} now={now} />
          ) : (
            <NotConnected what="인증서 만료" source={CERT_SOURCE} />
          )}
        </section>

        <section className={styles.panel} aria-labelledby="sec-access">
          <PanelHead id="sec-access" title="관리 접근 기록" meta="세션 발급 · 승인 발급·사용·거부 · 권한 거부" />
          {feed.res.loading ? (
            <PageSkeleton columns={[1]} rows={[3]} label="관리 접근 기록을 불러오는 중입니다" />
          ) : feed.res.error || access === null ? (
            <Unavailable what="관리 접근 기록" source={sim ? "관리 어댑터의 감사 기록" : "감사 로그(GET /admin/v1/audit)"} reason="감사 조회 실패" />
          ) : access.length === 0 ? (
            <div className={styles.empty}>
              <span className={styles.emptyMark} aria-hidden="true" />이 기간 관리 접근 기록 없음
            </div>
          ) : (
            <ActionTable rows={access.slice(0, ACCESS_ROWS)} now={now} onOpen={setOpen} columns="access" />
          )}
          <div className={secStyles.accessFoot}>
            <span className={secStyles.accessNotes}>
              <span>
                운영자 접속 경로 <span className={secStyles.pathValue}>{path}</span>
              </span>
              <span>{sim ? "관리 어댑터의 감사 기록에서 거릅니다" : "감사 로그의 받아 온 쪽에서만 거릅니다"}</span>
            </span>
            {access && access.length > 0 && (
              <Link href="/admin/actions?category=SESSION" className={secStyles.moreLink}>
                조치 기록에서 {access.length > ACCESS_ROWS ? `${access.length}건 모두` : "보기"}
              </Link>
            )}
          </div>
        </section>
      </div>

      <section className={styles.panel} aria-labelledby="sec-ports">
        <PanelHead id="sec-ports" title="공개 포트 점검" />
        <NotConnected what="공개 포트 점검" source={PORT_SCAN_SOURCE} />
        <p className={secStyles.boundary}>
          <span className={secStyles.boundaryText}>
            <span className={secStyles.boundaryKey}>경계 방화벽</span>
            <KeepNames text={BOUNDARY_NOTE} />
          </span>
          <CommandChip command={BOUNDARY_SOURCE} kind="path" />
        </p>
      </section>

      {open && feed.rows && (
        <ActionDetail id={open} rows={feed.rows} approvals={feed.feed?.approvals ?? []} sim={sim} partial={feed.feed?.partial ?? false} onClose={() => setOpen(null)} />
      )}
    </div>
  );
}
