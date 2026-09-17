---
name: exchange-docs
description: 이 저장소의 설명서·보고서·설계 문서를 작성하거나 읽기 쉽게 고친다. 독자별 문서 위치, 근거 보존, 링크 검사에 사용한다. 기능 구현이나 단순 코드 리뷰만 하는 작업에는 적용하지 않는다.
---

# 프로젝트 문서 작성

저장소 루트의 [AGENTS.md](../../../AGENTS.md), [문서 작성 기준](../../../docs/ai/writing.md), [공동 작업 절차](../../../docs/ai/workflow.md)의 해당 절을 읽는다. 이 파일에 세부 규칙을 복사하지 않는다.

요청에서 대상과 독자를 파악한다. 사람이 처음부터 읽을 설명은 reports, 구현 조건은 reference, AI 행동 지시는 ai, 날짜별 실험은 measurements에 둔다. 기존 문서로 충분하면 새 파일을 만들지 않는다.

관련 코드와 원본 자료를 확인하고 설계 요구·구현 상태·검증 결과를 구분한다. 문장은 실제 동작과 이유를 연결해 쓰며, 숫자·단위·조건·실패 처리·근거를 보존한다. 문체를 고치라는 요청을 소스 변경 권한으로 확대하지 않는다.

작성 후 원문과 사실을 대조하고 처음 읽는 사람의 입장에서 다시 읽는다. 저장소 루트에서 다음을 실행한다.

```powershell
pwsh -NoProfile -File scripts/check-docs.ps1
pwsh -NoProfile -File scripts/check-doclinks.ps1
pwsh -NoProfile -File scripts/check-adr-current.ps1
pwsh -NoProfile -File scripts/check-style.ps1
```

새 문서는 스타일 검사를 해당 경로로도 실행한다. 검사는 문장 품질이나 내용의 정확성을 보장하지 않는다. 실제 바꾼 문서와 검증 결과, 아직 읽지 않았거나 확인하지 못한 범위를 간결하게 보고한다.
