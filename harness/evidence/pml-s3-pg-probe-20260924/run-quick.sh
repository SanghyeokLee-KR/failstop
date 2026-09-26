#!/usr/bin/env bash
# ADR 0034 S3 설계 3절 P1·P5·P6 실행기(Git Bash). 폐기용 postgres:17-alpine 컨테이너 하나를 --rm 과 127.0.0.1 무작위
# 포트로 띄우고 지운다. 카오스 스택(chaos-*), 보존 볼륨, 영속 DB 는 건드리지 않는다.
# 사용: bash run-quick.sh > quick.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3-${RANDOM}${RANDOM}"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

python "$HERE/gen_cases.py" > "$HERE/cases.csv"
java "$(to_host "$HERE/UpCheck.java")" < "$HERE/cases.csv" > "$HERE/java.csv"

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /probe
for f in quick-probe.sh cases.csv; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/$f")" "$NAME:/probe/$f"
done
MSYS_NO_PATHCONV=1 docker exec "$NAME" sh /probe/quick-probe.sh
MSYS_NO_PATHCONV=1 docker cp "$NAME:/probe/pg.csv" "$(to_host "$HERE/pg.csv")"

echo "== P1 대조(Java setScale(s, UP) 대 PostgreSQL 식). NUMERIC 값 동등으로 비교한다"
python - "$HERE/java.csv" "$HERE/pg.csv" "$HERE/cases.csv" <<'EOF'
import csv, sys
from decimal import Decimal
java = {r['id']: r['java'] for r in csv.DictReader(open(sys.argv[1], encoding='utf-8'))}
pg = {row[0]: row[1] for row in csv.reader(open(sys.argv[2], encoding='utf-8'))}
cases = {r['id']: r for r in csv.DictReader(open(sys.argv[3], encoding='utf-8'))}
diff = [i for i in java if Decimal(java[i]) != Decimal(pg[i])]
text_diff = [i for i in java if java[i] != pg[i]]
rounded_up = sum(1 for i, r in cases.items() if Decimal(r['price']) * Decimal(r['qty']) != Decimal(java[i]))
print(f"cases={len(java)} pg_rows={len(pg)} mismatches={len(diff)} text_mismatches={len(text_diff)} rounded_up_cases={rounded_up}")
for i in text_diff[:5]:
    print('TEXT_MISMATCH', i, cases[i], java[i], pg[i])
for i in diff[:10]:
    print('MISMATCH', i, cases[i], java[i], pg[i])
for i in ['1', '3', '5', '6', '9', '10', '14', '16', '19']:
    print('sample', i, cases[i]['price'], cases[i]['qty'], cases[i]['scale'], 'java=' + java[i], 'pg=' + pg[i])
EOF
