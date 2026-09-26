/**
 * 보안 그룹 허용 경로의 설계 의도(관리자 설계 4-10-3 네트워크·보안). AWS에서 읽은 값이 아니다. 행은 Terraform 예제의 배치와
 * 공개 범위(개인 계정 목표 구성)를 옮기고, 학교 계정에 콘솔로 적용한 기록을 따로 적는다. 두 데이터 원천이 같은 정적 표다.
 * 원문이 바뀌면 이 표를 같은 변경에서 고친다.
 */

import type { AllowedPath } from "./types";

const TF = "deploy/terraform/README.md";
const AWS = "docs/reports/AWS_작업.md";

export const ALLOWED_PATHS: AllowedPath[] = [
  {
    from: "인터넷(CloudFront, 허용한 고객 주소, 봇 출발 주소)",
    to: "ALB",
    port: "443",
    purpose: "HTTPS 진입. enable_public_access를 켤 때만 엽니다",
    source: TF,
    school: "ALB 없음(생성 권한 거부). 앱 SG에 80·443 전체 허용, 80은 인증서 검증과 HTTPS 이동",
  },
  {
    from: "ALB",
    to: "app-1·app-2·app-3",
    port: "8080",
    purpose: "주문과 조회",
    source: TF,
    school: "앱 Nginx가 443을 받아 루프백의 앱으로 넘깁니다. 8080·8081은 루프백만",
  },
  {
    from: "ALB 검사, bot-1",
    to: "앱",
    port: "8081",
    purpose: "헬스와 지표",
    source: TF,
    school: "앱 지표는 Nginx 9101 대리로 봇 SG에서만(sgr-0a21d2a6470ed76ba)",
  },
  {
    from: "앱·app-3·bot-1·DB",
    to: "DB",
    port: "5432",
    purpose: "DB 연결과 복제",
    source: TF,
    school: "앱 SG와 DB SG 자신, 봇 SG(sgr-0fdf7835ee54f9a75)",
  },
  {
    from: "bot-1(관측)",
    to: "DB 9187, 모든 노드 9100",
    port: "9187 · 9100",
    purpose: "지표 수집",
    source: TF,
    school: "9100·9187은 봇 SG에서만",
  },
  {
    from: "관측 보안 그룹 안",
    to: "보고 서비스",
    port: "9088",
    purpose: "보고 수신",
    source: TF,
    school: "봇 9088은 앱 SG에서만(sgr-0e7d1928623e2ad5d). 봇 3000·8443도 앱 SG에서만",
  },
  {
    from: "운영자",
    to: "노드",
    port: "SSH 22 없음",
    purpose: "SSM 포트 전달로 localhost 2222의 키 전용 SSH",
    source: TF,
    school: "SSH 22는 서울 EC2 Instance Connect 접두사 목록에서만",
  },
  {
    from: "노드",
    to: "인터넷",
    port: "나가는 통신",
    purpose: "이미지, SSM, 보고. NAT 없이 노드의 공인 IPv4로 나갑니다",
    source: TF,
    school: null,
  },
  {
    from: "관제(bot-1)",
    to: "ai-1",
    port: "11434",
    purpose: "AI 판단 요청(Ollama). 연결 경로 결정 전(ADR 0038)",
    source: "docs/reference/adr/0038-on-prem-ai-inference-server.md",
    school: null,
  },
];

/** 학교 적용 기록의 원문 */
export const SCHOOL_SG_SOURCE = AWS;

/**
 * 경계 방화벽 안내 한 줄. pfSense는 VM 실습망의 경계 방화벽 자리이고 AWS 경로에는 두지 않았다(사용자 결정). VM에 이미
 * 구성했는지는 저장소 기록과 다르게 전달받아(관리자 설계 7-10 (n)) 이 줄은 구성 여부를 말하지 않는다
 */
export const BOUNDARY_NOTE =
  "pfSense는 VM 실습망의 경계 방화벽 자리이고 AWS 경로에는 두지 않았습니다. 학교 계정은 라우트 변경이 거부돼 장비를 트래픽 경로에 넣을 수 없고, AWS의 경계는 보안 그룹과 NACL이 맡습니다.";

export const BOUNDARY_SOURCE = "deploy/vm-lab/문서/VM_방화벽_실습_계획.md";

/** 공개 포트 점검 원천. 외부 프로브가 없어 두 원천 모두 연결 전이다 */
export const PORT_SCAN_SOURCE = "외부 프로브 없음(GET /admin/v1/msp/security · 3라운드 계약 초안)";
