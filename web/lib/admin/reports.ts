/**
 * 운영 보고의 표시 규칙(관리자 설계 4-7). 보고서 화면과 'Teams로 보낸 카드' 미리보기가 보고 서비스와 같은
 * 정보 구조를 쓰게 한다. 카드는 보고 서비스의 카드 생성(deploy/reporting/presentation.py card)을 옮긴 것이고
 * 필드의 순서와 문구도 그대로다. 미리보기는 Teams의 모양을 흉내 내지 않고 이 콘솔의 판면으로 그린다.
 */

import { kst } from "./timeText";
import type { InterventionCode, OpsReport, ReportJobStatus } from "./types";

export type CardTone = "attention" | "warning" | "good";

export type CardBlock =
  | {
      type: "text";
      text: string;
      size?: "small" | "large";
      bolder?: boolean;
      subtle?: boolean;
      tone?: CardTone;
      /** 카드 제목 앞의 상태 기호. 보고 서비스는 그림 문자를 쓰고 이 콘솔은 형태 기호로 옮긴다 */
      shape?: string;
      separator?: boolean;
    }
  | { type: "facts"; facts: { title: string; value: string }[]; separator?: boolean };

export interface TeamsCard {
  blocks: CardBlock[];
  /** 카드 아래 단추. 파일이 없으면 보고 서비스는 단추를 붙이지 않는다 */
  actions: { title: string; url: string }[];
}

/** 카드 제목의 색과 기호. 해제는 초록, 즉시 대응은 장애, 조치·확인은 주의, 관찰 유지는 초록이다 */
export function cardTone(r: OpsReport): { tone: CardTone; shape: string } {
  if (r.transition === "resolved") return { tone: "good", shape: "●" };
  const byCode: Record<InterventionCode, { tone: CardTone; shape: string }> = {
    immediate: { tone: "attention", shape: "■" },
    scheduled: { tone: "warning", shape: "▲" },
    verify: { tone: "warning", shape: "▲" },
    monitor: { tone: "good", shape: "○" },
  };
  return byCode[r.intervention.code];
}

/** 보고 서비스가 보낸 Adaptive Card 본문과 같은 순서의 블록 */
export function teamsCard(r: OpsReport): TeamsCard {
  const t = cardTone(r);
  const blocks: CardBlock[] = [
    { type: "text", text: `페일스톱 운영 관제 · ${r.customer} / ${r.environment}`, size: "small", subtle: true },
    { type: "text", text: r.title, size: "large", bolder: true, tone: t.tone, shape: t.shape },
    { type: "text", text: `${r.intervention.priority} · ${r.intervention.label}`, bolder: true },
  ];
  const first = r.events[0];
  blocks.push({
    type: "facts",
    facts: first
      ? [
          { title: "대상", value: first.target },
          { title: "발생", value: kst(first.startedAt) },
          ...(first.endedAt ? [{ title: "해제", value: kst(first.endedAt) }] : []),
        ]
      : [{ title: "보고 기준", value: kst(r.collectedAt) }],
  });
  blocks.push({ type: "text", text: `영향  |  ${r.impact}`, size: "small" });
  blocks.push({ type: "text", text: "담당자 조치", bolder: true, separator: true });
  for (const a of r.actions.slice(0, 2)) {
    blocks.push(
      { type: "text", text: `${a.priority} · ${a.owner} · ${a.timing}`, bolder: true, size: "small" },
      { type: "text", text: a.task, size: "small" },
      { type: "text", text: `완료: ${a.doneWhen}`, size: "small", subtle: true },
    );
  }
  const seen = r.metrics.filter((m) => m.status !== "unknown").slice(0, 4);
  if (seen.length > 0) {
    blocks.push(
      { type: "text", text: `후속 관측 · ${kst(r.collectedAt)}`, bolder: true, size: "small", separator: true },
      { type: "facts", facts: seen.map((m) => ({ title: m.label, value: m.value })) },
    );
  }
  if (r.analysis.summary) {
    blocks.push({ type: "text", text: "참고 분석", bolder: true, separator: true }, { type: "text", text: r.analysis.summary, size: "small" });
  }
  if (r.gaps.length > 0) blocks.push({ type: "text", text: `미확인 자료 ${r.gaps.length}항목 · 상세 보고의 수집 범위 참조`, size: "small", subtle: true });
  blocks.push(
    { type: "text", text: r.analysis.note, size: "small", subtle: true, separator: true },
    { type: "text", text: `기록 ${r.id.slice(-12)} · ${r.autoAction}`, size: "small", subtle: true },
  );
  const actions: TeamsCard["actions"] = [];
  if (r.links.pdf) actions.push({ title: "PDF 보고서", url: r.links.pdf });
  if (r.links.html) actions.push({ title: "HTML 보고서", url: r.links.html });
  return { blocks, actions };
}

/** 전송 결과 글자. sent는 웹훅이 접수했다는 뜻이고 채널 게시를 보장하지 않는다(보고 서비스 README) */
export const JOB_WORD: Record<ReportJobStatus, { shape: string; word: string; tone: "success" | "warning" | "danger" | "off" }> = {
  sent: { shape: "●", word: "전송됨", tone: "success" },
  pending: { shape: "○", word: "대기", tone: "off" },
  processing: { shape: "○", word: "작성 중", tone: "off" },
  queued: { shape: "▲", word: "재시도 대기", tone: "warning" },
  sending: { shape: "○", word: "전송 중", tone: "off" },
  dead: { shape: "■", word: "전송 실패", tone: "danger" },
};

export const KIND_WORD = { daily: "일일 보고", incident: "사건 보고" } as const;

/** 개입 등급의 형태. 즉시 대응 ■, 조치·확인 ▲, 관찰 유지 ● */
export const INTERVENTION_SHAPE: Record<InterventionCode, { shape: string; tone: "danger" | "warning" | "success" }> = {
  immediate: { shape: "■", tone: "danger" },
  scheduled: { shape: "▲", tone: "warning" },
  verify: { shape: "▲", tone: "warning" },
  monitor: { shape: "●", tone: "success" },
};
