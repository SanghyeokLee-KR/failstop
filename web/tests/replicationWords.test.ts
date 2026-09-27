/**
 * DB 복제·승격 문구(lib/admin/replicationWords.ts). http 판은 수집값을 적고 모르면 미수집·미확인이며, 수집값을 싣지 않는 sim 판은
 * 시뮬레이터의 ADR 0024 구성 문구 그대로다.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { promotionWord, replicationWord, standbyWord } from "../lib/admin/replicationWords";

test("엄격 동기와 Patroni 자동 승격은 수집값 그대로 적는다", () => {
  assert.equal(`PostgreSQL 17 · ${replicationWord("SYNC")} · ${promotionWord("AUTO")}`, "PostgreSQL 17 · 엄격 동기 복제 · 자동 승격(Patroni)");
  assert.equal(standbyWord("SYNC"), "예비 DB · 엄격 동기");
  assert.equal(replicationWord("ASYNC"), "비동기 복제");
  assert.equal(promotionWord("MANUAL"), "수동 승격(Patroni 일시 정지)");
});

test("모르면 지어내지 않고 미수집·미확인이다", () => {
  assert.equal(replicationWord(null), "복제 방식 미수집");
  assert.equal(standbyWord(null), "예비 DB · 복제 방식 미수집");
  assert.equal(promotionWord(null), "승격 방식 미확인", "Patroni 지표가 없다고 수동으로 단정하지 않는다");
});

test("수집값을 싣지 않는 sim 판은 원래 문구 그대로다", () => {
  assert.equal(`PostgreSQL 17 · ${replicationWord(undefined)} · ${promotionWord(undefined)}`, "PostgreSQL 17 · 비동기 복제 · 승격은 수동");
  assert.equal(standbyWord(undefined), "Standby · 비동기 복제");
});
