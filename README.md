# Linux·AWS 기반 인프라 운영 및 온프레미스 AI 관제 시스템 구축

개발팀이 거래소와 대시보드 개발을 마친 상황을 가정하고, 인프라 담당자가 AWS 운영 환경을 설계·구축·검증하는 프로젝트다. 약 3개월간 직접 개발한 가상 거래소 페일스톱과 기존 대시보드를 운영 대상으로 사용한다. Linux 서버 설정, 네트워크 설계, 부하 대응과 복구를 다루며 월 40만 원의 운영 예산을 기준으로 계획한다. 봇과 사용자가 가상 자산을 사고팔며 실제 돈이나 암호화폐는 다루지 않는다.

Linux 서버와 네트워크·스토리지·DB를 운영하고, 대시보드에서 이상을 찾아 대응하는 과정을 만든다. 역할별 배포와 관측, DB 수동 복구·백업 복원, Microsoft Teams의 일일·장애 보고와 PDF 제공을 구현해 로컬에서 검증했다. AWS의 실제 배포·조회 계층 자동 확장은 계정 준비 후 확인할 범위다. 기존 로컬 Docker 카오스 결과는 거래 서비스의 개발 검증 자료로 보존한다.

거래 엔진, 회원, 게시판, 관리자 기능을 운영 대상으로 삼는다. 로컬 운영 검증이 AWS 운영 완료를 뜻하지는 않는다. 최종 발표는 2026-10-16이며 HTML 발표 자료와 사전 녹화한 영상·캡처로 준비한다(발표 13분, 시연 7분). 현재 범위와 구현 전제는 [ADR 0027](docs/reference/adr/0027-msp-operations-and-autoscaling.md), 진행 상태는 [로드맵](docs/ROADMAP.md), 이번 로컬 검증은 [09-18 기록](docs/measurements/20260918.md)에서 확인한다.

## 처음 읽는다면

진행 순서는 **개발팀의 Docker 검증 결과 확인 → 학교 EC2에서 Linux 운영·서비스 검증 → 2026년 10월 초 개인 AWS 계정에서 보강**이다. VM 선행 단계는 보류한다. Linux에 직접 설치한 앱·DB·관측 도구를 OS 서비스로 운영하고 재부팅·디스크·호스트 간 통신과 복원을 확인한다. 학교 계정의 제한은 [ADR 0029](docs/reference/adr/0029-school-aws-first-personal-account-expansion.md), 실제 화면과 명령 기록은 [증거본](증거본/README.md)에서 확인한다.

- [프로젝트 이해하기](docs/reports/프로젝트_이해하기.md): 운영 대상 거래소의 구조와 MSP 운영으로 이어갈 일.
- [0099 현재 구조](docs/reference/adr/0099-current-architecture.md): 지금 적용하는 결정, 구조와 검증 한계. AWS 전에 판단할 사항도 여기서 시작한다.
- [코드 지도](docs/reference/architecture/프로젝트_코드지도.md): 기능을 수정할 때 열어볼 소스와 테스트.
- [로드맵](docs/ROADMAP.md): 현재 상태와 남은 작업. AWS 조건은 [배포 안내](deploy/README.md)에 있다.
- [아키텍쳐 자료](아키텍쳐/README.md): 교수님께 보고한 구성도·화면과 현재 구현의 차이, 영상·캡처 준비.

전체 문서 목록은 [문서 안내](docs/README.md), 단계별 완료 기준은 [로드맵](docs/ROADMAP.md)에 있다.

## 저장소 구성

| 폴더 | 내용 |
|---|---|
| [server/](server/) | Java 매칭 엔진과 Spring Boot API. 거래 기록, 정산, 복구, 봇을 포함한다. |
| [web/](web/) | Next.js 거래 화면. 정적 파일로 빌드해 배포한다. |
| [harness/](harness/) | 부하·장애 실험, 데이터 검사, 모니터링 설정과 실험 결과. |
| [deploy/](deploy/) | Linux 운영·역할별 배포, 관측·Teams 보고, DB 복구·백업과 AWS Terraform. |
| [seed-data/](seed-data/) | 외부 시세 데이터 수집 도구와 결과. 앱에 자동으로 적재하는 연결은 아직 없다. |
| [docs/](docs/) | 사람용 설명서, 상세 설계, AI 작업 규칙과 측정 결과. |
| [아키텍쳐/](아키텍쳐/README.md) | 교수님 보고본 원본, 시연 영상과 캡처를 모으는 폴더. |

## 실행

웹과 서버를 함께 띄우려면 [실행 가이드](실행_가이드.md)를 따른다. Node.js 20 이상, JDK 21, Docker Desktop이 필요하다. 아래 명령은 각각 저장소 루트에서 시작한다.

웹 개발 서버:

```powershell
cd web
npm ci
npm run dev
```

웹은 기본 설정에서 브라우저 내 시뮬레이터를 쓴다. 실제 백엔드 연결에 필요한 환경 변수는 실행 가이드에서 확인한다.

백엔드 빌드와 테스트:

```powershell
cd server
.\gradlew.bat build
```

통합 테스트는 Docker로 PostgreSQL을 띄운다. Windows에서는 한글이 없는 저장소 경로를 사용한다. 자세한 조건은 [서버 안내](server/README.md)에 있다.

Claude와 Codex의 공통 작업 규칙은 [AGENTS.md](AGENTS.md), CI 설정은 [.github/workflows/](.github/workflows/)에 있다.
