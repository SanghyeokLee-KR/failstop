# 제출본 빌드

제출물 세 종은 손으로 만들지 않는다. 정본에서 생성한다. 정본이 바뀌면 다시 굽는다.

## 산출 경로

| 산출물 | 정본 | 생성기 |
|---|---|---|
| `기획안.docx` | `기획안_원본.md` | `md2docx.py` |
| `기획안.pdf` | `기획안.docx` | `docx2pdf`(Word COM) |
| `기획안_요약.pptx` | `deck.js` 안에 인라인 | `deck.js`(pptxgenjs) |
| `diagram/시스템_아키텍처.png` | `diagram/arch_aws.mjs` | `arch_aws.mjs` + `render.mjs` |

## 실행

```powershell
powershell -NoProfile -File 제출_20260901/build/build.ps1
```

`pwsh` 로도 돈다. 스크립트가 UTF-8 BOM 이라 Windows PowerShell 5.1 에서도 한글이 깨지지 않는다.
BOM 을 빼면 5.1 이 스크립트를 cp949 로 읽어 파서 오류가 난다.

개별 실행은 `build.ps1` 본문의 각 단계를 그대로 떼어 쓴다.

## 선행 조건

| 항목 | 확인 |
|---|---|
| `python-docx` | `python -c "import docx"` |
| PDF 변환기 | LibreOffice 또는 `docx2pdf`. 아래 참조 |
| `pptxgenjs` | 전역 설치. `npm ls -g pptxgenjs` |
| `sharp` | `제출_20260901/diagram/node_modules`. 그 폴더에서 `npm install` 한 번 |

PDF 는 둘 중 있는 것을 쓴다. `build.ps1` 이 순서대로 찾는다.

| 변환기 | 요구 | 비고 |
|---|---|---|
| LibreOffice | 없음 | `soffice --headless --convert-to pdf`. Word 없이 돈다. 전용 프로필로 실행해 창이 떠 있어도 변환된다 |
| `docx2pdf` | Microsoft Word | Word COM 이라 Word 가 깔린 PC 에서만 돈다. LibreOffice 가 없을 때만 쓴다 |

AWS 아이콘은 더 이상 외부 패키지에 의존하지 않는다. `diagram/icons/` 에 21종이 들어 있고
`diagram/lib/draw.mjs` 가 그것을 읽는다. 파이썬 `diagrams` 패키지는 필요 없다.

## 주의

`deck.js` 는 슬라이드 내용을 파일 안에 인라인으로 갖는다. `기획안_원본.md` 에서 자동으로 끌어오지
않는다. 본문 수치를 고치면 `deck.js` 의 같은 수치도 함께 고쳐야 한다. 두 곳이 갈라지면
제출본 안에서 문서와 요약이 서로 다른 숫자를 말하게 된다.

`deck.json` 은 초기 슬라이드 구성안이다. `deck.js` 가 읽지 않는다. 이력으로만 남긴다.

수치의 정의는 `기획안_원본.md` 3.1 규모 실측이 정한다. 서버 코드 줄수는
`engine main + app main` 합이고 테스트를 포함하지 않는다. 웹 줄수는 테스트를 포함한다.
정의가 다르면 같은 저장소에서 다른 숫자가 나온다.
