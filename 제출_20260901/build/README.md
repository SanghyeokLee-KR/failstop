# 제출본 빌드

제출물 세 종은 손으로 만들지 않는다. 정본에서 생성한다. 정본이 바뀌면 다시 굽는다.

## 산출 경로

| 산출물 | 정본 | 생성기 |
|---|---|---|
| `기획안.docx` | `기획안_원본.md` | `md2docx.py` |
| `기획안.pdf` | `기획안.docx` | `docx2pdf`(Word COM) |
| `기획안_요약.pptx` | `deck.js` 안에 인라인 | `deck.js`(pptxgenjs) |
| `diagram/시스템_아키텍처.png` | `diagram/arch_aws.py` | `arch_aws.py` + `render.mjs` |

## 실행

```powershell
pwsh -NoProfile -File 제출_20260901/build/build.ps1
```

개별 실행은 `build.ps1` 본문의 각 단계를 그대로 떼어 쓴다.

## 선행 조건

| 항목 | 확인 |
|---|---|
| `python-docx` | `python -c "import docx"` |
| `docx2pdf` | `python -c "import docx2pdf"`. Word 가 설치돼 있어야 한다 |
| `pptxgenjs` | 전역 설치. `npm ls -g pptxgenjs` |
| `sharp` | `web/node_modules/sharp`. 웹 의존성에 이미 있다 |
| AWS 아이콘 | `diagrams` 파이썬 패키지의 `resources` 디렉터리. `arch_aws.py` 상단 `ICON` 이 경로를 잡는다 |

## 주의

`deck.js` 는 슬라이드 내용을 파일 안에 인라인으로 갖는다. `기획안_원본.md` 에서 자동으로 끌어오지
않는다. 본문 수치를 고치면 `deck.js` 의 같은 수치도 함께 고쳐야 한다. 두 곳이 갈라지면
제출본 안에서 문서와 요약이 서로 다른 숫자를 말하게 된다.

`deck.json` 은 초기 슬라이드 구성안이다. `deck.js` 가 읽지 않는다. 이력으로만 남긴다.

수치의 정의는 `기획안_원본.md` 3.1 규모 실측이 정한다. 서버 코드 줄수는
`engine main + app main` 합이고 테스트를 포함하지 않는다. 웹 줄수는 테스트를 포함한다.
정의가 다르면 같은 저장소에서 다른 숫자가 나온다.
