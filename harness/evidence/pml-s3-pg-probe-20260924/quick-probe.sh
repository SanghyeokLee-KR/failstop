#!/bin/sh
# 컨테이너 안(run-quick.sh 가 부른다). ADR 0034 S3 설계 3절 P1·P5·P6 의 PostgreSQL 동작을 작은 스키마로 확인한다.
P="psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0"
q() { $P -c "$1" 2>&1; }

echo "== P1a power(10::numeric, -s) 의 값과 표시 자릿수(s = 0..18)"
$P <<'EOF'
SELECT s, power(10::numeric, -s)::text, scale(power(10::numeric, -s))
FROM generate_series(0, 18) s ORDER BY s;
EOF

echo "== P1b 올림 양자화 식. order_reserve_amount 계약의 지정가 매수 가지와 같은 식이다"
$P <<'EOF'
CREATE TABLE cases (id INT PRIMARY KEY, price TEXT, qty TEXT, scale INT);
CREATE FUNCTION up_q(v NUMERIC, s INT) RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE r NUMERIC := trunc(v, s);
BEGIN
    IF r < v THEN
        r := r + power(10::NUMERIC, -s);
    END IF;
    -- 값은 바꾸지 않고 표시 자릿수만 s 로 맞춘다. Java setScale(s, UP) 의 toPlainString 과 글자까지 같게 하려는 것이다.
    RETURN round(r, s);
END $$;
EOF
$P -c "\copy cases FROM '/probe/cases.csv' WITH (FORMAT csv, HEADER true)"
$P -c "SELECT count(*) AS cases FROM cases"
# 결과 파일은 호스트가 Java 기준값과 대조한다. 숫자 비교는 NUMERIC 동등(표시 자릿수 무시)으로 한다.
$P -c "\copy (SELECT id, up_q(price::numeric * qty::numeric, scale)::text FROM cases ORDER BY id) TO '/probe/pg.csv' WITH (FORMAT csv)"
echo "== P1c NUMERIC(40,18) 저장 캐스트 뒤에도 같은가(원장 게시가 쓰는 캐스트). 다른 행 수"
$P -c "SELECT count(*) FROM cases WHERE up_q(price::numeric * qty::numeric, scale) <> up_q(price::numeric * qty::numeric, scale)::numeric(40,18)"
echo "== P1d 양자화 검증식(V38 post_ledger_transaction 의 round 비교)을 통과하지 못하는 결과 수. 0 이어야 한다"
$P -c "SELECT count(*) FROM cases WHERE up_q(price::numeric * qty::numeric, scale)::numeric(40,18) <> round(up_q(price::numeric * qty::numeric, scale)::numeric(40,18), scale)"

echo "== P5 함수 속성 SET lock_timeout 이 함수 안의 행 잠금 대기에 걸리고 함수가 끝나면 풀리는가"
$P <<'EOF'
CREATE TABLE lease (resource TEXT PRIMARY KEY, epoch BIGINT);
INSERT INTO lease VALUES ('matching-engine', 1), ('matching-p0', 0), ('matching-p1', 0);
CREATE FUNCTION lock_all_leases() RETURNS INT LANGUAGE plpgsql SET lock_timeout = '1s' AS $$
DECLARE n INT;
BEGIN
    PERFORM 1 FROM lease ORDER BY resource FOR UPDATE;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;
EOF
# 세션 A 가 한 행에 KEY SHARE 를 쥔 채 4초 머문다. 세션 B 는 세션 상한 없이 함수를 부른다.
( $P <<'EOF'
BEGIN;
SELECT 'A key share', epoch FROM lease WHERE resource = 'matching-engine' FOR KEY SHARE;
SELECT pg_sleep(4);
COMMIT;
EOF
) > /tmp/p5a.out 2>&1 &
sleep 1
$P <<'EOF'
\set VERBOSITY verbose
SHOW lock_timeout;
SELECT to_char(clock_timestamp(), 'HH24:MI:SS.MS') AS start;
SELECT lock_all_leases();
SELECT to_char(clock_timestamp(), 'HH24:MI:SS.MS') AS after_error;
SHOW lock_timeout;
EOF
wait
cat /tmp/p5a.out
echo "== P5b 잠금 경합이 없으면 함수는 세 행을 잡고 끝난다"
q "SELECT lock_all_leases()"

echo "== P6 SECURITY DEFINER 함수 안의 CHECK 위반(23514)이 부른 쪽에 제약 이름을 그대로 넘기는가"
$P <<'EOF'
CREATE TABLE bal (account_id BIGINT PRIMARY KEY, balance NUMERIC(40,18) NOT NULL,
                  CONSTRAINT chk_balance_nonneg CHECK (balance >= 0));
INSERT INTO bal VALUES (1, 100), (2, 0);
CREATE FUNCTION inner_post(p_amount NUMERIC) RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    UPDATE bal SET balance = balance - p_amount WHERE account_id = 1;
    UPDATE bal SET balance = balance + p_amount WHERE account_id = 2;
END $$;
CREATE FUNCTION outer_prologue(p_amount NUMERIC) RETURNS INT LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp AS $$
BEGIN
    PERFORM inner_post(p_amount);
    RETURN 1;
END $$;
\set VERBOSITY verbose
SELECT outer_prologue(150);
EOF
