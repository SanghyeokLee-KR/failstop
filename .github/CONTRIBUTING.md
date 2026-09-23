# 기여 규칙

공통 작업 기준은 [AGENTS.md](../AGENTS.md)에 있다. Claude와 Codex가 같은 기준을 사용한다. 문체의 상세 기준은 [문서 작성](../docs/ai/writing.md)과 [코드·주석 작성](../docs/ai/code-style.md)을 따른다.

## 브랜치

- `main`: 보호 브랜치. PR로만 병합. CI green 필수.
- `develop`: 통합 브랜치.
- 사용자에게 지정받은 이름이 없으면 AI 작업 브랜치는 `codex/<작업명>`을 쓴다. 기존 브랜치를 임의로 바꾸지 않는다.

## 커밋

- 제목: `type: 요약`. type은 feat, fix, refactor, docs, test, chore, perf 중 하나.
- 제목 50자 이내, 명사형. 본문은 이유·영향·검증을 필요한 만큼 기록한다.
- 금지: 이모지, em dash(U+2014)·en dash(U+2013), 도구 서명(Generated with, Co-Authored-By: Claude 등).
- 작성 도구의 상투적인 서명을 덧붙이지 않는다. 변경 동작과 이유를 구체적으로 설명한다.

예시.

```
feat: 주문 접수 멱등 처리 추가

client_order_id 중복 요청을 replay로 응답한다.
재시도 폭주 시 이중 접수를 차단하기 위함.
```

## PR

- 템플릿(.github/PULL_REQUEST_TEMPLATE.md)을 채운다.
- 리뷰어 1인 이상 승인. 셀프 리뷰 후 요청한다.
- CI 통과 필수: 빌드, 테스트, 스타일 게이트(style.yml).
- 계약 문서(docs/)와 어긋나는 변경은 문서 갱신을 같은 PR에 포함한다.

## 산출물 스타일 (요약)

1. em dash·en dash 금지. 쉼표, 마침표, 가운뎃점으로 나눈다.
2. 이모지 금지.
3. 문서는 자연스러운 평서문으로 쓴다. 목록과 표는 나열·절차·비교가 필요할 때 사용한다.
4. 주석은 이유만. 코드를 반복 설명하지 않는다.
5. 짧고 근거 있게. 같은 말을 반복하지 않는다.

## 릴리스

- 마일스톤과 현재 상태는 [로드맵](../docs/ROADMAP.md)을 따른다.
- 릴리스할 때 버전 태그와 변경 내용을 정리한다. 평소 작업마다 별도 변경 기록 파일을 만들지 않는다.

## 설계 결정 기록(ADR)

- 되돌리기 어려운 결정(스택 변경, 계약 변경, 데이터 모델 변경)은 `docs/reference/adr/NNNN-title-with-dashes.md`(4자리 순번, 소문자 kebab-case 제목)로 남긴다. 0099는 현재 구조용 예약 번호다.
- 형식: 상태, 배경, 결정, 근거, 대안, 영향. 상태 어휘와 번호 규칙은 [ADR 0001](../docs/reference/adr/0001-record-architecture-decisions.md)을 따른다. 한 페이지를 넘기지 않는다.
