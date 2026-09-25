/**
 * 부하 시험 기록(관리자 설계 4-10-3 용량·부하). 측정 문서의 날짜와 한 줄 요약이다. 요약은 측정 폴더 README의 날짜별 표를
 * 옮긴 것이고, 부하·용량과 무관한 기록(09-18 운영 검증, 09-25 모델 평가)은 싣지 않는다. 모두 로컬 Docker 측정이라 AWS
 * 성능이 아니다. 배포한 웹은 저장소 문서를 서빙하지 않으므로 화면은 경로 복사 칩으로 둔다.
 */

export interface LoadTestRecord {
  /** KST 날짜 */
  day: string;
  summary: string;
  path: string;
  /** 앱 계층 증설 경로처럼 용량 판단에 바로 쓰는 기록인가 */
  capacity: boolean;
}

const DIR = "docs/measurements";

export const LOAD_TESTS: LoadTestRecord[] = [
  { day: "2026-09-23", summary: "로컬 조회 증설 경로(분산·조정기·조회 부하·종단 리허설)와 E4 한 회차", path: `${DIR}/20260923.md`, capacity: true },
  { day: "2026-09-15", summary: "스냅샷 재생, commit_delay 조건, 이벤트 순번 확대", path: `${DIR}/20260915.md`, capacity: false },
  { day: "2026-09-14", summary: "마켓별 접수·채번 분리와 장애 실험", path: `${DIR}/20260914.md`, capacity: false },
  { day: "2026-09-13", summary: "접수 동시성, 조회 인덱스, 검사 SQL", path: `${DIR}/20260913.md`, capacity: false },
  { day: "2026-09-12", summary: "잠금 구간 분리와 당시 남은 문제", path: `${DIR}/20260912.md`, capacity: false },
  { day: "2026-09-11", summary: "기준선, 접수 잠금과 저널 기록 비용", path: `${DIR}/20260911.md`, capacity: false },
  { day: "2026-09-10", summary: "부하 조정, 커넥션 풀, 채번 경합", path: `${DIR}/20260910.md`, capacity: false },
  { day: "2026-09-09", summary: "처리량·지연, DB 규격, 실험 유효성", path: `${DIR}/20260909.md`, capacity: false },
];
