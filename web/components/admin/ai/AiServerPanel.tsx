"use client";

/**
 * AI 서버 ai-1 상태(관리자 설계 4-10-2, ADR 0038). 관제 판의 AI 서버 칸을 판독 묶음 하나로 읽는다. 구축 전이라 sim은 붙어서
 * 판단 요청에 답한다고 가정하므로 '시뮬레이션 가정'과 '구축 전 · 연결 경로 결정 전'을 함께 적고, '연결됨'을 쓰지 않는다.
 * http는 관제 서버가 잰 모델·적재 메모리·호출 지연과 연결만 적고 나머지는 원천 연결 전이다.
 */

import type { AiServer, OpsSection } from "@/lib/admin/types";
import { secWord } from "@/lib/admin/timeText";
import { SimMark, StatStrip, StatTile } from "../ui";
import { Ago, PanelHead, SectionGap } from "../control/parts";
import controlStyles from "../control/control.module.css";
import styles from "./ai.module.css";

const NC = "원천 연결 전";

export default function AiServerPanel({ section }: { section: OpsSection<AiServer> }) {
  return (
    <section className={controlStyles.panel} aria-labelledby="ai-server">
      <PanelHead
        id="ai-server"
        title="AI 서버 · ai-1"
        meta={section.state === "READY" && section.data.assumed ? "구축 전 · 연결 경로 결정 전" : "온프레미스 · 연구소"}
        actions={section.state === "READY" && section.data.assumed ? <SimMark kind="assumed" /> : undefined}
      />
      {section.state !== "READY" ? (
        <SectionGap section={section} what="AI 서버" compact />
      ) : (
        <Body a={section.data} />
      )}
    </section>
  );
}

function Body({ a }: { a: AiServer }) {
  const state = a.link === "DOWN" ? "연결 끊김" : a.busy ? "판단 요청 처리 중" : "대기";
  return (
    <>
      <StatStrip label="ai-1 판독" count={6}>
        <StatTile label="GPU 사용률" value={a.gpuPct ?? "◌"} unit={a.gpuPct === null ? undefined : "%"} sub={a.gpuPct === null ? NC : "판단 요청이 걸린 동안 오릅니다"} />
        <StatTile
          label="통합 메모리"
          value={a.memUsedGb ?? "◌"}
          unit={a.memUsedGb === null ? undefined : a.memTotalGb === null ? "GB" : `/${a.memTotalGb}GB`}
          sub={a.memTotalGb === null ? "장비 메모리 원천 연결 전 · 적재 모델만" : "적재 모델과 캐시"}
        />
        <StatTile
          label="추론 지연 p95"
          value={a.p95Ms === null ? "요청 없음" : secWord(a.p95Ms)}
          sub={a.p50Ms === null ? "최근 판단 요청 없음" : `p50 ${secWord(a.p50Ms)}`}
        />
        <StatTile label="처리량" value={a.tokensPerSec ?? "◌"} unit={a.tokensPerSec === null ? undefined : "토큰/초"} sub={a.tokensPerSec === null ? NC : "생성 토큰"} />
        <StatTile label="대기 요청" value={a.queue ?? "◌"} unit={a.queue === null ? undefined : "건"} sub={a.queue === null ? NC : state} />
        <StatTile label="적재 모델" value={<span className={styles.model}>{a.model}</span>} sub={a.assumed ? "평가로 정합니다" : a.runtime} />
      </StatStrip>
      <dl className={styles.srvMeta}>
        <dt>장비</dt>
        <dd>
          {a.hardware} · {a.runtime}
        </dd>
        <dt>관제 연결</dt>
        <dd>{a.assumed ? "가정 · 연결 경로 결정 전(ADR 0038)" : a.link === "OK" ? "연결됨" : "끊김"}</dd>
        <dt>마지막 응답</dt>
        <dd className="num">{a.lastServedAt === null ? "응답 기록 없음" : <Ago at={a.lastServedAt} />}</dd>
      </dl>
      {a.assumed && (
        <p className={controlStyles.lead}>
          ai-1은 아직 구축 전이라 시뮬레이터가 판단 요청에 답한다고 가정해 값을 만듭니다. 판단 자체는 시뮬레이터 규칙이 내고 모델을 부르지 않습니다.
        </p>
      )}
    </>
  );
}
