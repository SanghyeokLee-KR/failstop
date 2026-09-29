/**
 * 원인 분석과 오류 로그의 표시 글자(API 설계 §6-F v2.65, 디자인 시스템 §12-12). AI 패널이 그리고 웹 단위 시험이 본다.
 * 인용 값은 서버 단위(비율 0..1, 초)로 오므로 여기서 단위에 맞춰 적는다.
 */

import { bytesWord } from "./dbFacts";
import { durationWord, hms, secWord, stamp } from "./timeText";
import type { AlertSeverity, CauseAnalysis, CauseCitation, CauseUnit, LoadPattern, LoadVerdict } from "./types";

export const LOAD_WORD: Record<LoadPattern, string> = {
  SUSTAINED: "지속 부하",
  TRANSIENT: "일시적 부하",
  NONE: "부하 아님",
  UNKNOWN: "판단 불가",
};

const SEVERITY_TEXT: Record<AlertSeverity, string> = { critical: "장애", warning: "주의", info: "정보" };

/** 크기에 맞춘 소수 자리. 10 이상은 첫째 자리, 그 아래는 둘째 자리까지 적고 끝의 0은 지운다 */
function num(n: number): string {
  return String(Number(n.toFixed(Math.abs(n) >= 10 ? 1 : 2)));
}

/** 비율을 백분율 숫자로. 10% 이상은 정수, 1% 이상은 첫째 자리, 그 아래는 둘째 자리다 */
function pctNum(ratio: number): string {
  const p = ratio * 100;
  const a = Math.abs(p);
  return String(Number(p.toFixed(a >= 10 ? 0 : a >= 1 ? 1 : 2)));
}

/** 값 하나를 단위와 함께. 비율은 %, 초는 ms나 초, 초당은 건/초, 바이트는 크기, 건수는 건이다 */
export function unitValue(n: number, unit: CauseUnit | null): string {
  switch (unit) {
    case "ratio":
      return `${pctNum(n)}%`;
    case "seconds":
      return secWord(n * 1000);
    case "per_second":
      return `${num(n)}건/초`;
    case "bytes":
      return bytesWord(n);
    case "count":
      return `${num(n)}건`;
    default:
      return num(n);
  }
}

/** 이전 값의 글자. 기호 단위(%, ms, 초, 바이트)는 붙이고 낱말 단위(건, 건/초)는 앞의 값이 이미 적어 뺀다 */
function sideValue(n: number, unit: CauseUnit | null): string {
  return unit === "per_second" || unit === "count" || unit === null ? num(n) : unitValue(n, unit);
}

/** 변화의 글자. 상태 요약 증감과 같이 ▲▼와 크기이고 비율의 변화는 %p다 */
function changeText(n: number, unit: CauseUnit | null): string {
  if (n === 0) return "±0";
  const size = unit === "ratio" ? `${pctNum(Math.abs(n))}%p` : sideValue(Math.abs(n), unit);
  return `${n > 0 ? "▲" : "▼"}${size}`;
}

export interface CitationParts {
  /** 경보 인용이면 그 등급. 모르는 등급(other)은 경보 칸과 같이 장애로 다룬다 */
  severity: AlertSeverity | null;
  value: string;
  before: string | null;
  change: string | null;
  /** 시각 앞에 붙이는 말. 경보는 시작, 오류 로그는 마지막 발생, 부하 판정은 판정 시각이다 */
  atWord: string | null;
}

const AT_WORD: Partial<Record<CauseCitation["kind"], string>> = { alert: "시작", log: "마지막", load: "판정" };

/** 근거 인용 칩의 글자. 모델 문장 속 숫자가 아니라 서버가 붙인 값이다 */
export function citationParts(c: CauseCitation): CitationParts {
  const atWord = AT_WORD[c.kind] ?? null;
  if (c.kind === "alert") {
    const known = c.text === "critical" || c.text === "warning" || c.text === "info";
    const severity: AlertSeverity = known ? (c.text as AlertSeverity) : "critical";
    return { severity, value: known ? SEVERITY_TEXT[severity] : "등급 모름", before: null, change: null, atWord };
  }
  if (c.text !== null) {
    const value = c.kind === "load" ? LOAD_WORD[c.text as LoadPattern] ?? c.text : c.text;
    return { severity: null, value, before: null, change: null, atWord };
  }
  return {
    severity: null,
    value: c.value === null ? "값 없음" : unitValue(c.value, c.unit),
    before: c.before === null ? null : sideValue(c.before, c.unit),
    change: c.before === null || c.change === null ? null : changeText(c.change, c.unit),
    atWord,
  };
}

/** 설정 기간(지속 기준, 창)은 분이 딱 떨어지면 '3분'으로, 지난 기간은 '3분 45초'로 적는다(§12-12) */
function periodWord(sec: number): string {
  return sec >= 60 && sec % 60 === 0 ? `${sec / 60}분` : durationWord(sec * 1000);
}

/**
 * 부하 판정의 특징을 항목마다 한 덩이로. '앱 계층 CPU 72% · 기준 50% · 3분 45초 연속(지속 기준 3분) · 15분 안 초과 1회 ·
 * 요청률 상관 0.93'처럼 이어 그린다. 모르는 특징은 적지 않고, CPU와 기준은 모르면 그 사실을 적는다
 */
export function loadFacts(l: LoadVerdict): string[] {
  const out = [
    l.cpuPct === null ? "앱 계층 CPU 미수집" : `앱 계층 CPU ${l.cpuPct}%`,
    l.thresholdPct === null ? "기준 미설정" : `기준 ${l.thresholdPct}%`,
  ];
  const above = l.aboveForSec ?? 0;
  if (above > 0) out.push(`${durationWord(above * 1000)} 연속${l.sustainSec === null ? "" : `(지속 기준 ${periodWord(l.sustainSec)})`}`);
  const within = l.windowSec === null ? "" : `${periodWord(l.windowSec)} 안 `;
  if (l.excursions === 0) out.push(`${within}초과 없음`);
  else if (l.excursions !== null) {
    out.push(`${within}초과 ${l.excursions}회`);
    // 지금 초과 중이면 연속 시간이 같은 사실을 싣는다. 지난 초과만 있을 때 그 길이를 적는다
    if (above === 0 && l.aboveTotalSec) out.push(`초과 합 ${durationWord(l.aboveTotalSec * 1000)}`);
  }
  if (l.requestCorrelation !== null) out.push(`요청률 상관 ${l.requestCorrelation.toFixed(2)}`);
  if (l.coveragePct !== null && l.coveragePct < 100) out.push(`수집 표본 ${l.coveragePct}%`);
  return out;
}

/**
 * 근거 부족 줄의 사유. 모델이 부족한 것을 적었거나 남은 가설이 없으면 문장을, 아니면 null을 돌려준다. 모델이 실패한 분석은
 * 실패 줄이 따로 서므로 null이다
 */
export function lackingReason(a: CauseAnalysis): string | null {
  if (a.state !== "READY") return null;
  if (a.insufficient) return a.insufficient;
  return a.hypotheses.length === 0 ? "근거를 인용한 원인 가설이 없습니다" : null;
}

/**
 * 오류 로그의 처음·마지막 발생 시각. 처음 시각은 앱이 뜬 뒤 처음이라 며칠 전일 수 있어 기록 시각 한 형식(§12-12)으로 적는다.
 * 오늘은 수집한 시각의 KST 날짜로 가른다
 */
export function seenWord(iso: string | null, polledAt: string | null): string {
  if (iso === null) return "미확인";
  return polledAt === null ? hms(iso) : stamp(iso, Date.parse(polledAt));
}
