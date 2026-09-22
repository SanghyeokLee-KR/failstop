-- Databasus 가 exchange DB 를 읽을 때 쓰는 계정을 만든다. DB01 에서 postgres 로 실행한다.
--
-- 왜 슈퍼유저를 쓰지 않는가
--   백업 도구에 슈퍼유저를 주면 그 도구가 뚫렸을 때 DB 전체를 잃는다. 고객사 DB 에 붙는
--   계정이 읽기만 한다는 것을 권한 조회와 쓰기 거부 결과로 증명할 수 있어야 한다.
--
-- 논리 백업(pg_dump)에 필요한 권한은 읽기다. 물리 백업(pg_basebackup)은 복제 프로토콜을
-- 쓰므로 REPLICATION 속성이 따로 필요하다. 이 파일은 1단계인 논리 백업 범위만 만든다.
-- 물리 백업을 켜기로 하면 그때 REPLICATION 을 별도 결정으로 기록하고 더한다.
--
-- 비밀번호는 이 파일에 넣지 않는다. 역할을 만든 뒤 psql 의 \password 로 직접 넣는다.
-- 명령줄 인자로 넣으면 셸 히스토리와 프로세스 목록에 남는다.

-- INHERIT 여야 한다. NOINHERIT 로 두면 pg_read_all_data 의 권한이 자동으로 적용되지 않고
-- 세션에서 SET ROLE 을 해야 쓸 수 있다. pg_dump 는 SET ROLE 을 하지 않으므로 읽기가 막힌다.
CREATE ROLE databasus_ro WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT
    CONNECTION LIMIT 4;

-- pg_read_all_data 는 PostgreSQL 14 부터 있는 기본 역할이다. 모든 스키마의 모든 테이블을
-- SELECT 할 수 있게 하되 쓰기 권한은 주지 않는다. 테이블이 늘어도 따로 GRANT 하지 않아도 된다.
-- PostgreSQL 16 부터 상속 여부가 역할 속성이 아니라 GRANT 시점에 고정된다
-- (pg_auth_members.inherit_option). 역할을 NOINHERIT 로 만든 뒤 ALTER ROLE ... INHERIT 로
-- 바꿔도 이미 준 멤버십은 따라오지 않는다. 그래서 WITH INHERIT TRUE 를 명시한다.
GRANT pg_read_all_data TO databasus_ro WITH INHERIT TRUE;

-- 연결할 DB 를 지정한다. 다른 DB 로는 붙지 못한다.
GRANT CONNECT ON DATABASE exchange TO databasus_ro;

-- 새 객체를 만들지 못하게 public 스키마의 CREATE 를 명시적으로 회수한다.
REVOKE CREATE ON SCHEMA public FROM databasus_ro;
