/**
 * 불변식 요약 판정. 이 판정의 요점은 "빈 표를 통과로 읽지 않는다"이다.
 *
 * 분모가 서버가 돌려준 행 수이면 표가 비었을 때 0 < 0이 거짓이 되어 관제 최상단이
 * "전체 정상 / 0 · 0"을 띄운다. 학교 AWS는 하네스를 끈 채 운영하므로 빈 표가 실재하는
 * 환경이다. 분모를 기대 검사 수로 두고, 그 기대가 서버 enum과 어긋나지 않는지 이 파일이 대조한다.
 *
 * 대조는 웹 시험을 돌릴 때만 일어난다. CI의 ci-web은 web/** 변경에만 트리거되고 ci-backend는
 * 웹 시험을 돌리지 않으므로, 서버 enum만 바꾼 변경은 이 대조 없이 통과한다. 어긋남은 그 뒤 웹을
 * 건드리는 변경에서야 실패로 드러난다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { summarizeInvariants, SCHEDULED_INVARIANTS } from "../lib/admin/invariants";
import { INVARIANT_DEFS } from "../lib/admin/catalog";
import type { InvariantCheck } from "../lib/admin/types";

function check(name: string, ok: boolean, checkedAt = "2026-09-23T00:10:00Z"): InvariantCheck {
  return { name, label: name, ok, violatedRows: ok ? 0 : 3, detail: "", checkedAt };
}

const AUTHORITATIVE_ONLY = INVARIANT_DEFS.filter((d) => d.scope === "AUTHORITATIVE_ONLY").map((d) => d.name);
const scheduledRows = (ok = true) => SCHEDULED_INVARIANTS.map((n) => check(n, ok));

test("카탈로그 이름과 범위는 서버 InvariantCheck enum과 같다", () => {
  // .test-dist/tests에서 저장소 루트까지 세 단계다
  const src = readFileSync(
    path.resolve(__dirname, "..", "..", "..", "server/app/src/main/java/kr/exchange/harness/InvariantCheck.java"),
    "utf8",
  );
  const server = [...src.matchAll(/^\s+(C\d+[A-Z0-9_]*)\(Severity\.[A-Z]+,\s*Scope\.([A-Z_]+)/gm)].map(
    (m) => `${m[1]}:${m[2]}`,
  );
  assert.ok(server.length > 0, "서버 enum을 읽지 못함");
  const web = INVARIANT_DEFS.map((d) => `${d.name}:${d.scope}`);
  assert.deepEqual([...web].sort(), [...server].sort());
});

test("빈 표는 통과가 아니라 미수집이다", () => {
  const s = summarizeInvariants([]);
  assert.equal(s.state, "NONE");
  assert.equal(s.ok, 0);
  // 분모는 서버 응답 길이가 아니라 기대 검사 수다. 0/0이 만들어지지 않는다
  assert.equal(s.expected, SCHEDULED_INVARIANTS.length);
  // 검사 시각을 현재 시각으로 채우지 않는다. 안 돈 배치가 방금 돈 것으로 읽히면 안 된다
  assert.equal(s.checkedAt, null);
});

test("정기 검사가 전부 통과하면 권위 run 없이도 PASS다", () => {
  const s = summarizeInvariants(scheduledRows());
  assert.equal(s.state, "PASS");
  assert.equal(s.ok, SCHEDULED_INVARIANTS.length);
  assert.equal(s.expected, SCHEDULED_INVARIANTS.length);
  assert.equal(s.violated, 0);
});

test("권위 run 뒤에는 실린 전용 검사가 분모에 더해져 통과 수가 분모를 넘지 않는다", () => {
  const s = summarizeInvariants([...scheduledRows(), ...AUTHORITATIVE_ONLY.map((n) => check(n, true))]);
  assert.equal(s.state, "PASS");
  assert.equal(s.ok, INVARIANT_DEFS.length);
  assert.equal(s.expected, INVARIANT_DEFS.length);
});

test("정기 검사가 하나라도 빠지면 권위 전용 행이 있어도 부분 수집이다", () => {
  const rows = [...scheduledRows().slice(1), ...AUTHORITATIVE_ONLY.map((n) => check(n, true))];
  const s = summarizeInvariants(rows);
  assert.equal(s.state, "PARTIAL");
  assert.equal(s.ok, rows.length);
  assert.ok(s.ok < s.expected);
});

test("위반은 미수집을 이긴다. 표본이 모자라도 측정된 실패가 더 강한 사실이다", () => {
  const rows = [...scheduledRows().slice(0, 3), check("C99_UNKNOWN", false)];
  const s = summarizeInvariants(rows);
  assert.equal(s.state, "VIOLATED");
  assert.equal(s.violated, 1);
  assert.equal(s.ok, 3);
  // 카탈로그에 없는 이름도 실린 사실이라 분모에 들어간다
  assert.equal(s.expected, SCHEDULED_INVARIANTS.length + 1);
});

test("검사 시각은 행들 중 최신이다", () => {
  const s = summarizeInvariants([
    check("C01_ASSET_CONSERVATION", true, "2026-09-23T00:05:00Z"),
    check("C02_BAL_VS_LEDGER", true, "2026-09-23T00:20:00Z"),
    check("C03_TXN_UNSEALED", true, "2026-09-23T00:11:00Z"),
  ]);
  assert.equal(s.checkedAt, "2026-09-23T00:20:00Z");
});
