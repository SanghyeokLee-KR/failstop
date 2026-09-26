#!/bin/sh
# 컨테이너 안: V0~V47 을 번호 순서로 파일마다 한 트랜잭션에 적용하고, 시드, 계약 V48, 확인, 계약 V49, 확인.
P="psql -X -U postgres -d contract -At"
for f in $(ls /contract/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  $P -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "applied V0..V47: $(ls /contract/migration/V*.sql | wc -l) files"
$P -q -1 -v ON_ERROR_STOP=1 -f /contract/contract-seed.sql >/dev/null || { echo "SEED FAILED"; exit 1; }
$P -q -1 -v ON_ERROR_STOP=1 -f /contract/v48.sql 2>&1
echo "v48 exit=$?"
$P -v ON_ERROR_STOP=0 -f /contract/contract-check-p1.sql 2>&1
$P -q -1 -v ON_ERROR_STOP=1 -f /contract/v49.sql 2>&1
echo "v49 exit=$?"
$P -v ON_ERROR_STOP=0 -f /contract/contract-check-p2.sql 2>&1
echo "== D5 하네스 검증설계 §4-1 C29 술어 원문(트리거를 끈 마켓 하나가 남은 상태라 위반 1행)"
$P -v ON_ERROR_STOP=0 -f /contract/c29.sql 2>&1
