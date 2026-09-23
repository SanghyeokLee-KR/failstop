/**
 * 불변식 요약 판정. 이 판정의 요점은 "빈 표를 통과로 읽지 않는다"이다.
 *
 * 종전 식은 `통과 수 < 서버가 돌려준 행 수`였다. 표가 비면 0 < 0이 거짓이 되어
 * 관제 최상단이 "전체 정상 / 0 · 0"을 띄웠다. 학교 AWS는 하네스를 끈 채 운영하므로
 * 빈 표가 실재하는 환경이고, 그 화면은 자산 보존을 거짓으로 단정한 것이었다.
 * 분모를 기대 검사 수로 옮겨 그 경로를 닫았고, 이 파일이 그것을 고정한다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { summarizeInvariants, INVARIANT_EXPECTED } from "../lib/admin/invariants";
import type { InvariantCheck } from "../lib/admin/types";

function check(name: string, ok: boolean, checkedAt = "2026-09-23T00:10:00Z"): InvariantCheck {
  return { name, label: name, ok, violatedRows: ok ? 0 : 3, detail: "", checkedAt };
}

const all = (n: number, ok = true) => Array.from({ length: n }, (_, i) => check(`C${i}`, ok));

test("빈 표는 통과가 아니라 미수집이다", () => {
  const s = summarizeInvariants([]);
  assert.equal(s.state, "NONE");
  assert.equal(s.ok, 0);
  // 분모는 서버 응답 길이가 아니라 기대 검사 수다. 0/0이 만들어지지 않는다
  assert.equal(s.expected, INVARIANT_EXPECTED);
  // 검사 시각을 현재 시각으로 채우지 않는다. 안 돈 배치가 방금 돈 것으로 읽히면 안 된다
  assert.equal(s.checkedAt, null);
});

test("기대 수만큼 전부 통과해야 PASS다", () => {
  const s = summarizeInvariants(all(INVARIANT_EXPECTED));
  assert.equal(s.state, "PASS");
  assert.equal(s.ok, INVARIANT_EXPECTED);
  assert.equal(s.violated, 0);
});

test("행 수가 기대에 못 미치면 통과가 아니라 부분 수집이다", () => {
  const s = summarizeInvariants(all(INVARIANT_EXPECTED - 1));
  assert.equal(s.state, "PARTIAL");
  assert.equal(s.ok, INVARIANT_EXPECTED - 1);
  assert.equal(s.violated, 0);
});

test("위반은 미수집을 이긴다. 표본이 모자라도 측정된 실패가 더 강한 사실이다", () => {
  const rows = [...all(3), check("C99", false)];
  const s = summarizeInvariants(rows);
  assert.equal(s.state, "VIOLATED");
  assert.equal(s.violated, 1);
  assert.equal(s.ok, 3);
});

test("검사 시각은 행들 중 최신이다", () => {
  const s = summarizeInvariants([
    check("C1", true, "2026-09-23T00:05:00Z"),
    check("C2", true, "2026-09-23T00:20:00Z"),
    check("C3", true, "2026-09-23T00:11:00Z"),
  ]);
  assert.equal(s.checkedAt, "2026-09-23T00:20:00Z");
});
