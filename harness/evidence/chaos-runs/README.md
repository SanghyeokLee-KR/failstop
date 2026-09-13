# 카오스 실험 판정 파일

실험 스크립트가 판정을 내리는 순간 이 디렉터리에 `<runId>.json` 을 하나 떨어뜨린다. 사람이 옮기지
않는다.

## 왜 필요한가

판정의 원천이 컨테이너 안에 있었다. 페일오버 실험은 `harness.failover_runs` 에, 불변식은
`invariant_checks` 에 적재하고, 비페일오버 실험은 콘솔에만 찍었다. 카오스 볼륨은 `down -v` 로
파기되므로 회차가 끝나면 증거가 함께 사라졌다. 기획안 12절 A5 가 "실험 판정 파일이 저장소에
남습니다" 를 완료 기준으로 둔다.

## 무엇이 들어 있나

| 필드 | 뜻 |
|---|---|
| `verdict` | 스크립트 종료코드를 정하는 값. PASS 면 exit 0 이다 |
| `code.commit` | 판정을 낸 하네스의 커밋 |
| `code.uncommittedPaths` | 그 시점에 `server`, `harness` 아래 커밋되지 않은 경로 수. `harness/evidence` 는 뺀다. 0 이어야 `code.commit` 이 판정을 낸 코드와 같다 |
| `code.imageId` | 서빙 노드 이미지 ID |
| `metrics` | 실험별 판정 입력 |
| `invariants` | 그 runId 의 `invariant_checks` 행 전부. 창(pre, inject, spike, post)별이다 |
| `invariantRows` | 위 행 수. 0 이면 판정 대상이 없는 파일이다 |
| `failover` | 페일오버 실험만. `failover_runs` 에 적재한 본문과 같다 |

## 쓰는 곳

`harness/scripts/lib.ps1` 의 `Save-VerdictFile` 이다.

비페일오버 실험(E1, E3, E8, E9)과 시계 가드 SKIP 은 `Write-Verdict` 가 부른다. 그 실험들은
`Write-Verdict` 뒤에 verdict 가 바뀌지 않는다.

페일오버 실험(E2, E4, E5, E6, E7)은 종료코드를 정하기 직전에 스크립트가 직접 부른다. E2 는 승격
안정성 게이트가, E6 는 스파이크 창 판정이 `failover_runs` 적재 뒤에 verdict 를 더 떨어뜨릴 수
있다. 적재 지점에서 쓰면 파일은 PASS 인데 종료코드는 1 인 거짓 기록이 생긴다.

## 한계

- 실험이 판정 전에 예외로 끝나면 파일이 없다. 판정이 없었다는 뜻이다.
- 저장이 실패해도 판정과 종료코드는 바뀌지 않는다. 대신 붉은 줄이 찍힌다.
- `invariants` 는 게이팅 면제를 적용하기 전의 원자료다. 판정은 `verdict` 와 `metrics` 가 갖는다.
