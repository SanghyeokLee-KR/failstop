# 프로젝트 안내

고객사의 IT 인프라를 구축·운영·관리하는 엔지니어의 역할을 수행하는 프로젝트다. 핵심은 Linux 서버 운영이며, 약 3개월간 직접 개발한 가상 거래소를 대상으로 AWS 인프라와 MSP 관제 환경을 구축한다. 운영 대상인 페일스톱에는 거래 엔진과 웹 화면이 구현되어 있다. 봇과 사용자가 가상 자산을 사고팔며 실제 돈이나 암호화폐는 다루지 않는다. 전체 프로젝트 제목은 아직 정하지 않았다.

앞으로는 Linux 서버와 네트워크·스토리지·DB를 운영하고, 대시보드에서 이상을 찾아 대응하는 과정을 보여준다. 부하에 따른 조회·API 계층의 EC2 증설과 축소, DB 장애 복구, 로그와 지표를 근거로 만든 일일·장애 AI 보고와 Microsoft Teams 웹훅 연결이 주요 과제다. 기존 로컬 Docker 카오스 결과는 거래 서비스의 개발 검증 자료로 보존한다.

2026-09-17 기록에서 거래 엔진, 회원, 게시판, 관리자 기능은 구현 완료로 정리했다. MSP 관제와 자동 증설, AI 운영 보고가 완료됐다는 뜻은 아니다. 최종 발표는 2026-10-16이며 PPTX와 사전 녹화한 영상·캡처로 준비한다. 현재 범위와 구현 전제는 [ADR 0027](docs/reference/adr/0027-msp-operations-and-autoscaling.md), 진행 상태는 [로드맵](docs/ROADMAP.md)에서 관리한다.

## 처음 읽는다면

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
| [deploy/](deploy/) | AWS 노드별 실행 설정과 DB 복제 스크립트. |
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
