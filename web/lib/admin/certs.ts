/**
 * 인증서 만료(관리자 설계 4-10-3). 학교 배포의 인증서 기록과 남은 날의 판정이다. 관제센터 운영 작업 줄, 네트워크·보안,
 * 사이드바 배지가 같은 판정(certTone)을 써서 세 자리가 같은 날과 같은 모양을 적는다.
 *
 * 기록은 AWS 작업 안내의 '도메인과 인증서', '어드민과 용량 관리', '인증서 만료' 절과 DB TLS 설정 표다. 공개 인증서의
 * 만료는 날짜만 기록돼 있어 시각을 짓지 않고 KST 날짜로 둔다. 남은 날은 KST 날짜의 차이다.
 */

import { kstDay } from "./timeText";
import type { CertificateInfo } from "./types";

/** 학교 배포의 인증서 기록. sim의 관제 판이 이 목록을 싣는다. http는 서버의 인증서 원천이 연결 전이다 */
export const SCHOOL_CERTIFICATES: CertificateInfo[] = [
  {
    domain: "관리 게이트웨이 내부 mTLS",
    purpose: "앱 Nginx에서 bot-1 관리 API(8443)로 가는 봇 관리자 경로",
    issuer: "사설 CA(prepare-admin-gateway.py)",
    expiresOn: "2026-11-19",
    expiresAt: "2026-11-18T15:48:00.000Z",
    utcDate: false,
    renewal: "자동 갱신 없음 · 서버·게이트웨이 인증서와 신뢰 저장소를 함께 교체",
    autoRenew: false,
    lastDryRunOn: null,
  },
  {
    domain: "www.sang.it.kr",
    purpose: "공개 HTTPS(거래 화면·API·WebSocket)",
    issuer: "Let's Encrypt",
    expiresOn: "2026-12-18",
    expiresAt: null,
    utcDate: false,
    renewal: "certbot-renew.timer 자동 갱신",
    autoRenew: true,
    lastDryRunOn: "2026-09-19",
  },
  {
    domain: "db-primary·db-standby TLS",
    purpose: "PostgreSQL 연결(앱·봇의 verify-full)",
    issuer: "노드에서 만든 서버 인증서",
    expiresOn: "2026-12-18",
    expiresAt: null,
    utcDate: true,
    renewal: "자동 갱신 없음",
    autoRenew: false,
    lastDryRunOn: null,
  },
];

/** 원천이 없을 때 붙일 원천의 이름 */
export const CERT_SOURCE = "인증서 만료 원천(관제 서버 운영 작업의 certificate · 연결 전)";

/** 두 KST 날짜('2026-09-25') 사이의 날 수 */
function dayDiff(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** 만료까지 남은 날. KST 오늘에서 만료 날짜까지이고 지났으면 음수다 */
export function certDaysLeft(c: Pick<CertificateInfo, "expiresOn">, now: number): number {
  return dayDiff(kstDay(now), c.expiresOn);
}

/** 남은 날의 판정. 7일 이내 장애, 30일 이내 주의다(사이드바 배지와 같은 기준) */
export function certTone(days: number): "success" | "warning" | "danger" {
  return days <= 7 ? "danger" : days <= 30 ? "warning" : "success";
}

/** 가장 먼저 만료되는 인증서. 목록이 비면 null이다 */
export function soonestCert(list: CertificateInfo[]): CertificateInfo | null {
  return list.reduce<CertificateInfo | null>((a, c) => (a === null || c.expiresOn < a.expiresOn ? c : a), null);
}

/** 만료 날짜 글자('11.19 00:48', '12.18'). UTC 날짜 기록은 그렇게 밝힌다 */
export function certDateWord(c: CertificateInfo): string {
  const [, m, d] = c.expiresOn.split("-").map(Number);
  if (c.expiresAt) {
    const k = new Date(Date.parse(c.expiresAt) + 9 * 3_600_000);
    return `${m}.${d} ${String(k.getUTCHours()).padStart(2, "0")}:${String(k.getUTCMinutes()).padStart(2, "0")}`;
  }
  return c.utcDate ? `${m}.${d} UTC` : `${m}.${d}`;
}
