/**
 * Grafana 대시보드 uid 목록. 컴포넌트가 아니라 데이터라 여기 둔다.
 *
 * 컴포넌트 파일에 두었더니 시연 대본이 보드 이름을 쓰려고 tsx를 import 하게 됐고,
 * 그러면 테스트 컴파일이 JSX와 경로 별칭을 함께 끌어온다. 목록은 목록대로 둔다.
 *
 * uid를 문자열로 받지 않고 목록으로 좁힌 이유. 오타가 나면 링크가 죽는데 죽은 링크는
 * 눌러 보기 전에는 보이지 않는다. 로컬 프로비저닝에 실재하는 것만 열거한다.
 * 07 디스크·용량(msp-storage)은 학교 배포본에만 있고 로컬에는 없어 여기 없다.
 */

/** harness/grafana/dashboards 프로비저닝에 실재하는 uid */
export type GrafanaBoard =
  | "msp-overview"
  | "msp-service"
  | "msp-infra"
  | "msp-db"
  | "msp-db-workload"
  | "msp-db-replication"
  | "proof-integrity"
  | "proof-recovery"
  | "proof-load-slo";

export const BOARD_TITLE: Record<GrafanaBoard, string> = {
  "msp-overview": "01 서비스 관제",
  "msp-service": "02 트래픽·Spring",
  "msp-infra": "03 AWS·서버",
  "msp-db": "04 DB 관제",
  "msp-db-workload": "05 SQL·세션",
  "msp-db-replication": "06 복제·복구",
  "proof-integrity": "① 체결 무결성 증명",
  "proof-recovery": "② 자동 복구 증명",
  "proof-load-slo": "③ 부하·SLO",
};
