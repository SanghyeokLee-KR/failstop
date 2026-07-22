# 고가용성 가상 암호화폐 거래소

봇이 실시간으로 매매하는 가상 거래소. 트래픽 폭주와 서버 장애에도 체결이 어긋나지 않고 자동 복구됨을 데이터로 증명하는 것이 최상위 목표다. 완전 시뮬레이션이며 실거래·실자산은 없다.

1년 주기 프로젝트. 웹(데스크톱 트레이딩 화면)이 메인 산출물이고 모바일 앱은 후순위다.

## 구조

```
server/   Spring Boot 3.5 백엔드 (Gradle 멀티모듈)
          engine/  순수 Java 매칭엔진(결정론 코어, Spring 의존 없음)
          app/     API, 저널, 원장, 리더 리스, 봇 플릿
web/      Next.js 데스크톱 웹 (정적 export, 고정형 1280px 기준)
docs/     정본 계약 문서 (DB, API, 디자인, 하네스, 로드맵). docs/README.md 참조
scripts/  로컬 도구 (check-style.ps1)
.github/  기여 규칙, PR 템플릿, CI(스타일 게이트)
.claude/  역할 하네스 정의 (backend-engineer, frontend-engineer, harness-engineer, reviewer)
CLAUDE.md 작업 규칙 정본 (스타일, 역할 체계, 기술 계약)
```

## 진행 상태

준비 단계(계약 문서, 골격, 검증 장치)는 완료. 본 구현은 로드맵 M1(코어 거래 + 고가용성 증명)을 진행 중이다.
상세는 docs/ROADMAP.md.

## 실행

웹 개발 서버.

```
cd web
npm install
npm run dev        # http://localhost:3000
npm run build      # 정적 export, out/ 산출
```

백엔드 빌드·테스트. 한글 경로에서 Gradle 테스트가 깨지므로 ASCII 정션에서 실행한다(server/README.md 참조).

```
cd C:\dev\exchange\server   # C:\dev\exchange 는 프로젝트 폴더 정션
.\gradlew.bat build
.\gradlew.bat test          # 통합테스트는 Docker(Testcontainers) 필요
```

검증 셸은 PowerShell을 쓴다. 통합테스트는 PostgreSQL 17 컨테이너를 띄운다.

## 규칙

- 산출물 스타일 규칙은 CLAUDE.md에 있다. em dash·이모지·AI 어투·도구 서명 금지. CI(.github/workflows/style.yml)와 scripts/check-style.ps1이 강제한다.
- 구현은 역할 하네스로 진행하고 reviewer 판정 후 병합한다.
- 코드가 정본 문서와 어긋나면 문서를 먼저 고치고 코드를 맞춘다.
