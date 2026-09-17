# 문서 안내

프로젝트를 처음 읽는다면 [프로젝트 이해하기](reports/프로젝트_이해하기.md)부터 읽으면 된다. 주문 한 건이 처리되는 과정부터 장애 복구와 현재 한계까지 한 흐름으로 설명한다.

고객사의 IT 인프라를 구축·운영·관리하는 역할에서 Linux 서버 운영을 중심으로 프로젝트를 진행한다. 약 3개월간 직접 개발한 가상 거래소가 운영 대상이다. AWS 인프라와 관제, DB 복구, EC2 자동 확장, AI 일일·장애 보고와 Microsoft Teams 연계 방향은 [ADR 0027](reference/adr/0027-msp-operations-and-autoscaling.md), 구현 순서는 [로드맵](ROADMAP.md)에 있다.

현재 채택한 구조와 AWS 전에 판단할 사항은 [0099 현재 구조](reference/adr/0099-current-architecture.md)에 모았다. 과거 ADR을 처음부터 모두 읽지 않아도 지금 적용하는 결정과 확인이 남은 부분을 볼 수 있다.

교수님께 이미 설명한 구성도와 화면은 프로젝트 루트의 [아키텍쳐 자료](../아키텍쳐/README.md)에 있다. 원본을 보존하면서 현재 구현과의 차이, 지원금 40만 원을 기준으로 한 영상·캡처 준비 방향을 정리했다.

| 필요한 내용 | 읽을 곳 |
|---|---|
| 전체 구조를 한 번에 이해하기 | [프로젝트 이해하기](reports/프로젝트_이해하기.md) |
| 현재 구조와 결정의 적용 관계 | [0099 현재 구조](reference/adr/0099-current-architecture.md) |
| 지금 끝낸 일과 남은 일 | [로드맵](ROADMAP.md) |
| Claude·Codex와 작업하는 방법 | [AI와 개발하기](reports/AI와_개발하기.md) |
| 기능을 고칠 때 열어볼 소스와 테스트 | [코드 지도](reference/architecture/프로젝트_코드지도.md) |
| API·DB·화면·운영의 상세 조건 | [상세 자료 안내](reference/README.md) |
| 실험 조건과 결과 | [측정 안내](measurements/README.md) |

## 폴더의 역할

```text
docs/
  reports/        사람이 읽는 프로젝트 설명과 작업 안내
  reference/      개발자와 AI가 확인하는 상세 설계·계약·결정
  ai/             Claude와 Codex의 공통 작업 규칙과 프롬프트
  measurements/  날짜별 측정 기록
  ROADMAP.md      현재 상태와 남은 작업
```

`reports`는 처음부터 이어 읽는 설명이다. `reference`는 필요한 조건을 찾아보는 자료다. 세부 조건은 계약 문서, 진행 상태는 로드맵, 현재 구조와 결정의 적용 관계는 0099, 결정의 이유와 이력은 개별 ADR에서 관리한다. 같은 내용을 각 문서에 전체 복사하지 않는다.

AI는 루트 [AGENTS.md](../AGENTS.md)를 시작점으로 삼는다. Claude의 [CLAUDE.md](../CLAUDE.md)도 같은 파일을 가져온다. 문서를 고치는 절차는 [문서 작성 기준](ai/writing.md), 바로 사용할 요청문은 [작업 프롬프트](ai/prompts.md)에 있다.

새 세션은 AGENTS → 0099 → 로드맵 순서로 읽는다. AWS·Linux 운영을 시작할 때는 ADR 0027과 배포 안내도 읽고 로드맵의 '다음 작업의 시작점'에서 이어간다. 다른 PC로 옮기는 방법과 일반 채팅에 파일을 전달하는 방법은 [AI와 개발하기](reports/AI와_개발하기.md)에 있다.

실행은 [실행 가이드](../실행_가이드.md), AWS 설정은 [배포 안내](../deploy/README.md)를 따른다. 실험 원자료는 [harness/evidence](../harness/evidence/)에 남긴다. 날짜별 결과는 그때의 코드와 환경에 한정된다.

과거 문서는 Git에서 확인한다. 원본 증거와 소스 주석에 남은 옛 경로는 [경로 대응표](ai/legacy-paths.json)로 찾을 수 있다. 발표는 PPTX로 준비하며, 삭제한 대본·인수인계·WBS를 다시 관리하지 않는다.
