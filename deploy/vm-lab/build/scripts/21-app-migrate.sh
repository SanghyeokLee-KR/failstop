#!/usr/bin/env bash
# APP01에서만 실행하는 스키마 적용이다. APP02는 적용된 스키마를 그대로 쓴다.
#
# 앱을 기동하지 않고 배포 JAR 안의 Flyway로 스키마만 적용한다. 앱 자체는
# SPRING_FLYWAY_ENABLED=false 로 뜨므로 기동이 스키마를 건드리지 않는다.
# 마이그레이션 계정은 소유자 exchange_owner 이고 실행 계정 exchange_app 과 분리한다.
# 두 계정과 권한은 DB01 사양이 만든다. 준비되기 전에는 이 스크립트가 실패한다.
#
# 선행 조건이 하나 더 있다. 02-정본결정.md 5절대로 exchange_owner 의 TCP 접속은 평소에 닫혀
# 있고 마이그레이션을 할 때만 연다. DB01 에서 창을 먼저 열어야 이 스크립트가 붙을 수 있다.
#
#   DB01에서  sudo bash 30-db-primary-setup.sh migrate-open
#   APP01에서 sudo bash 21-app-migrate.sh migrate
#   DB01에서  sudo bash 30-db-primary-setup.sh migrate-close
#
# migrate-open 은 pg_hba.conf 에 exchange_owner 의 hostssl 한 줄을 넣고 reload 한다.
# migrate-close 는 그 줄을 지우고 ALTER ROLE exchange_owner NOLOGIN 을 실행한다.
# 창을 열지 않은 상태에서 이 스크립트를 돌리면 접속 단계에서 멈추며, 그 이유를 화면에 적는다.
# 창을 열지 않았는데 성공한 것처럼 적지 않는다.
#
# 실행 예:
#   sudo bash 21-app-migrate.sh migrate
#   sudo bash 21-app-migrate.sh validate
#
# 비밀번호는 인자로 받지 않는다. 화면에 보이지 않게 직접 입력하거나 root 전용 파일로 준다.
# 인자로 주면 ps 출력과 셸 이력에 남는다.
#
# 이미 적용한 Flyway 파일은 고치지 않고 repair도 하지 않는다. 체크섬이 어긋나면
# 무엇이 바뀌었는지 먼저 확인한다.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
note() { printf '   %s\n' "$*"; }

usage() {
    cat <<'EOF'
사용법: sudo bash 21-app-migrate.sh <migrate|validate> [옵션]

  migrate   적용하지 않은 마이그레이션을 적용하고 이어서 검증한다
  validate  적용하지 않고 스키마와 이력만 검증한다

  실행 전에 DB01에서 sudo bash 30-db-primary-setup.sh migrate-open 으로 창을 연다.
  끝나면 DB01에서 sudo bash 30-db-primary-setup.sh migrate-close 로 닫는다.

옵션
  --jar PATH           기본 /opt/exchange/current/app.jar
  --source PATH        MigrateDatabase.java 위치. 기본 /home/ops/stage/linux/app/MigrateDatabase.java
  --db-url URL         기본값은 /etc/exchange/app.env 의 SPRING_DATASOURCE_URL 이다
  --db-user NAME       기본 exchange_owner
  --password-file PATH root 전용 파일에서 비밀번호를 읽는다. 첫 줄만 쓴다
  --shred-password     --password-file 을 읽은 뒤 지운다
  --skip-preflight     접속 사전 확인을 건너뛴다. 실패해도 Flyway 출력으로만 원인을 본다
  -h, --help           이 도움말
EOF
}

action=
jar=/opt/exchange/current/app.jar
source_file=/home/ops/stage/linux/app/MigrateDatabase.java
db_url=
db_user=exchange_owner
password_file=
shred_password=false
skip_preflight=false

[[ $# -gt 0 ]] || { usage; exit 1; }
case "$1" in
    migrate|validate) action=$1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "첫 인자는 migrate 또는 validate 다." ;;
esac
while (($#)); do
    case "$1" in
        --jar) jar=${2:?}; shift 2 ;;
        --source) source_file=${2:?}; shift 2 ;;
        --db-url) db_url=${2:?}; shift 2 ;;
        --db-user) db_user=${2:?}; shift 2 ;;
        --password-file) password_file=${2:?}; shift 2 ;;
        --shred-password) shred_password=true; shift ;;
        --skip-preflight) skip_preflight=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage; die "모르는 인자: $1" ;;
    esac
done

# ---------------------------------------------------------------- 1. 대상 확인
step "1. 대상 확인"
[[ $(id -u) == 0 ]] || die "root로 실행한다."
# APP02에서 실행하면 같은 스키마에 두 번 적용을 시도하게 된다. 주소로 막는다.
ip -4 -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -qx 192.168.10.21 \
    || die "APP01(192.168.10.21)에서만 실행한다. APP02는 이 단계를 건너뛴다."
[[ -f $jar ]] || die "JAR이 없다: $jar"
[[ -f $source_file ]] || die "MigrateDatabase.java 가 없다: $source_file"
command -v unzip >/dev/null || die "unzip 이 없다."
command -v java >/dev/null || die "java 가 없다."

# 단일 파일 소스 실행은 jdk.compiler 모듈을 쓴다. headless 패키지만 있어도 되는지는
# 여기서 실제로 확인한다. 없으면 java-21-openjdk-devel 을 설치한다.
java --list-modules 2>/dev/null | grep -q '^jdk.compiler' \
    || die "jdk.compiler 모듈이 없다. sudo dnf install -y java-21-openjdk-devel 후 다시 실행한다."
note "java: $(java -version 2>&1 | head -1)"

if [[ -z $db_url ]]; then
    [[ -f /etc/exchange/app.env ]] || die "app.env 가 없다. --db-url 을 준다."
    # 주석 줄에도 키 이름이 나오므로 줄 앞에서만 찾는다. URL 안의 = 는 값의 일부다.
    db_url=$(awk '/^SPRING_DATASOURCE_URL=/ { sub(/^[^=]*=/, ""); print }' /etc/exchange/app.env)
fi
[[ -n $db_url ]] || die "DB 접속 문자열을 찾지 못했다."
note "접속: $db_url"
note "계정: $db_user (소유자 계정이다. 실행 계정 exchange_app 이 아니다)"

# ---------------------------------------------------------------- 2. 추출
step "2. JAR에서 Flyway와 마이그레이션 SQL 추출"
work=$(mktemp -d /root/migrate.XXXXXX)
chmod 0700 "$work"
# 추출물과 환경변수가 남지 않게 어떤 경로로 끝나든 지운다.
trap 'rm -rf -- "$work"' EXIT

# BOOT-INF/lib 전체를 꺼낸다. flyway-core 가 어떤 라이브러리를 더 요구하는지 추측하지 않는다.
# 필요한 것만 고르면 버전이 바뀔 때 조용히 NoClassDefFoundError 가 난다.
unzip -q -o "$jar" 'BOOT-INF/lib/*' -d "$work/extract"
unzip -q -o "$jar" 'BOOT-INF/classes/db/migration/*' -d "$work/extract"
libs="$work/extract/BOOT-INF/lib"
sql="$work/extract/BOOT-INF/classes/db/migration"
[[ -d $libs && -d $sql ]] || die "추출 결과가 비어 있다. 부트 JAR이 맞는지 확인한다."
sql_count=$(find "$sql" -name '*.sql' | wc -l)
lib_count=$(find "$libs" -name '*.jar' | wc -l)
note "라이브러리 $lib_count 개, 마이그레이션 SQL $sql_count 개"
[[ $sql_count -gt 0 ]] || die "마이그레이션 SQL이 없다."
# 저장소 server/app/src/main/resources/db/migration 과 같은 파일이어야 한다.
note "저장소 원본 개수와 다르면 JAR과 소스가 어긋난 것이다. 확인 후 진행한다."

cp "$source_file" "$work/MigrateDatabase.java"

# ---------------------------------------------------------------- 3. 비밀번호
step "3. 마이그레이션 비밀번호"
password=
if [[ -n $password_file ]]; then
    [[ -f $password_file && ! -L $password_file ]] || die "일반 파일이 아니다: $password_file"
    [[ $(stat -c %u "$password_file") == 0 ]] || die "root 소유가 아니다: $password_file"
    [[ $(stat -c %a "$password_file") == 600 ]] || die "권한을 0600으로 둔다: $password_file"
    password=$(head -n 1 "$password_file")
else
    # -s 로 입력을 화면에 내지 않는다. 이 구간은 캡처하지 않는다.
    [[ -t 0 ]] || die "터미널이 아니다. --password-file 로 준다."
    read -rsp "   ${db_user} 비밀번호: " password
    printf '\n'
fi
[[ -n $password ]] || die "비밀번호가 비어 있다."

# ---------------------------------------------------------------- 3-1. 접속 사전 확인
# JDBC 접속 문자열을 psql 이 쓰는 값으로 나눈다. 형식이 다르면 1을 낸다.
pg_host=; pg_port=; pg_db=; pg_sslmode=; pg_sslrootcert=
parse_jdbc_url() {
    local url=$1 body query hostport kv
    [[ $url == jdbc:postgresql://* ]] || return 1
    body=${url#jdbc:postgresql://}
    query=""
    if [[ $body == *\?* ]]; then
        query=${body#*\?}
        body=${body%%\?*}
    fi
    [[ $body == */* ]] || return 1
    hostport=${body%%/*}
    pg_db=${body#*/}
    pg_host=${hostport%%:*}
    pg_port=5432
    if [[ $hostport == *:* ]]; then pg_port=${hostport##*:}; fi
    pg_sslmode=""
    pg_sslrootcert=""
    local old_ifs=$IFS
    IFS='&'
    for kv in $query; do
        case "$kv" in
            sslmode=*) pg_sslmode=${kv#sslmode=} ;;
            sslrootcert=*) pg_sslrootcert=${kv#sslrootcert=} ;;
        esac
    done
    IFS=$old_ifs
    [[ -n $pg_host && -n $pg_db ]] || return 1
    return 0
}

# 접속 실패 메시지에서 원인을 골라 사람이 읽을 안내로 바꾼다. 여기서 나누지 않으면
# 마이그레이션 창을 열지 않은 것, 비밀번호가 틀린 것, 역할이 잠긴 것이 모두 같은 실패로 보인다.
# 알아본 원인이 있으면 0, 없으면 1을 낸다.
explain_connect_failure() {
    local msg=$1
    case "$msg" in
        *"no pg_hba.conf entry"*)
            note "원인: DB01의 pg_hba.conf 에 ${db_user} 를 받아 주는 줄이 없다."
            note "      02-정본결정.md 5절대로 마이그레이션 창이 열려 있어야 한다."
            note "해결: DB01에서 sudo bash 30-db-primary-setup.sh migrate-open 을 먼저 실행한다."
            note "      그 단계가 hostssl exchange ${db_user} 192.168.10.21/32 scram-sha-256 한 줄을"
            note "      넣고 reload 한다. 끝나면 migrate-close 로 그 줄을 지운다."
            return 0 ;;
        *"permission denied to create role"*)
            note "원인: ${db_user} 에 CREATEROLE 속성이 없어 V0__roles.sql 의 CREATE ROLE 이 막혔다."
            note "      접속은 성공했으므로 마이그레이션 창 문제가 아니다."
            note "해결: DB01에서 postgres 로 app_role 과 harness_role 을 먼저 만든다."
            note "      V0 는 IF NOT EXISTS 로 감싸져 있어 이미 있으면 건너뛴다."
            note "      전역 CREATEROLE 을 주는 대신 두 역할에만 WITH ADMIN OPTION 을 준다."
            return 0 ;;
        # NOLOGIN 만으로 넓게 잡으면 실패한 SQL 문장에 그 단어가 들어 있을 때도 걸린다.
        # 실제 거절 문구인 'is not permitted to log in' 으로만 판정한다.
        *"not permitted to log in"*)
            note "원인: 역할 ${db_user} 가 NOLOGIN 이다. migrate-close 가 이미 돌았을 수 있다."
            note "해결: DB01에서 sudo bash 30-db-primary-setup.sh migrate-open 을 실행한다."
            note "      그 단계가 LOGIN 으로 되돌리고 pg_hba 줄을 넣는다."
            return 0 ;;
        *"password authentication failed"*)
            note "원인: ${db_user} 비밀번호가 DB01이 정한 값과 다르다."
            note "해결: DB01의 30-db-primary-setup.sh passwords 단계에서 정한 값을 그대로 넣는다."
            note "      값은 화면에 내지 않는다. --password-file 로 root 전용 0600 파일에서 읽어도 된다."
            return 0 ;;
        *"Connection refused"*|*"connection refused"*|*"refused"*|*"timed out"*|*"timeout"*)
            note "원인: DB01의 5432에 닿지 못했다. postgresql 이 내려갔거나 방화벽이 막았다."
            note "해결: DB01에서 systemctl is-active postgresql 과 nft list table inet vmlab 을 확인한다."
            note "      00-주소계획.md 포트 표는 DB01 5432 를 APP01·APP02·DB02 에만 연다."
            return 0 ;;
        *"certificate"*|*"SSL"*|*"ssl"*)
            note "원인: TLS 확인에 실패했다. sslmode=verify-full 이므로 인증서와 이름이 모두 맞아야 한다."
            note "해결: /etc/exchange/db01.crt 가 DB01의 서버 인증서인지 지문으로 대조하고,"
            note "      접속 주소가 인증서 SAN 에 들어 있는지 확인한다."
            return 0 ;;
    esac
    return 1
}

step "3-1. 접속 사전 확인 (마이그레이션 창)"
if $skip_preflight; then
    note "--skip-preflight 로 건너뛴다. 실패하면 Flyway 출력으로만 원인을 본다."
elif ! command -v psql >/dev/null; then
    note "psql 이 없어 사전 확인을 건너뛴다. sudo dnf install -y postgresql 로 넣으면 원인이 빨리 드러난다."
elif ! parse_jdbc_url "$db_url"; then
    note "접속 문자열을 해석하지 못해 사전 확인을 건너뛴다: $db_url"
else
    pre_rc=0
    pre_out=$(
        export PGPASSWORD="$password" PGCONNECT_TIMEOUT=5
        if [[ -n $pg_sslmode ]]; then export PGSSLMODE="$pg_sslmode"; fi
        if [[ -n $pg_sslrootcert ]]; then export PGSSLROOTCERT="$pg_sslrootcert"; fi
        psql -h "$pg_host" -p "$pg_port" -d "$pg_db" -U "$db_user" -w -Atq \
             -c "SELECT current_user" 2>&1
    ) || pre_rc=$?
    if [[ $pre_rc == 0 ]]; then
        note "접속 확인: ${pg_host}:${pg_port}/${pg_db} 에 ${pre_out} 로 붙었다. 창이 열려 있다."
    else
        note "접속 실패: $(printf '%s' "$pre_out" | head -1)"
        explain_connect_failure "$pre_out" \
            || note "위 메시지를 그대로 보고 원인을 찾는다. 알려진 실패 형태와 맞지 않는다."
        die "마이그레이션을 시작하지 않는다. 접속이 되지 않는 상태에서 적용했다고 적지 않는다."
    fi
fi

# ---------------------------------------------------------------- 4. 실행
step "4. Flyway $action"
# 환경변수로만 넘긴다. MigrateDatabase 가 세 값을 required 로 읽고 없으면 스스로 멈춘다.
# 출력은 화면과 파일에 함께 남긴다. 실패했을 때 원인을 같은 기준으로 다시 고르기 위해서다.
# 이 파일은 work 디렉터리(0700)에 있고 스크립트가 끝날 때 trap 이 지운다.
flyway_log="$work/flyway.log"
set +e
MIGRATION_DB_URL="$db_url" \
MIGRATION_DB_USER="$db_user" \
MIGRATION_DB_PASSWORD="$password" \
    java -cp "$libs/*" "$work/MigrateDatabase.java" "$action" "$sql" 2>&1 | tee "$flyway_log"
rc=${PIPESTATUS[0]}
set -e
unset password
if [[ $rc != 0 ]]; then
    note "Flyway $action 이 실패했다 (종료 코드 $rc)."
    explain_connect_failure "$(cat "$flyway_log")" \
        || note "접속 문제로 보이지 않는다. 체크섬 오류라면 repair하지 않고 무엇이 바뀌었는지 먼저 본다."
    die "Flyway $action 실패 (종료 코드 $rc). 위 원인을 먼저 해결한다."
fi

if [[ -n $password_file ]] && $shred_password; then
    shred -u "$password_file" 2>/dev/null || rm -f "$password_file"
    note "비밀번호 파일을 지웠다: $password_file"
fi

if [[ $action == migrate && -d /etc/exchange ]]; then
    # 이 노드에서 언제 적용했는지 남기는 기록이다. 기동 여부를 정하는 근거는 아니다.
    # 02-정본결정.md 9절대로 20-app-setup.sh 는 APP01·APP02 모두 DB 에 직접 질의해 판정한다.
    # 이 파일은 APP01 안에만 있어 APP02 에서는 보이지 않기 때문이다.
    date -u '+%Y-%m-%dT%H:%M:%SZ applied by 21-app-migrate.sh' > /etc/exchange/.migrated
    chmod 0644 /etc/exchange/.migrated
    note "기록 남김: /etc/exchange/.migrated (판정 근거가 아니라 이 노드의 작업 기록이다)"
fi

step "끝. 다음을 직접 확인한다"
cat <<EOF
   위 출력의 Applied migrations 개수와 Schema validation passed 문구
   psql 로 flyway_schema_history 의 success 여부
   DB01에서 sudo bash 30-db-primary-setup.sh migrate-close 로 창을 닫는다
   창을 닫으면 pg_hba 의 ${db_user} 줄이 빠지고 역할이 NOLOGIN 이 된다
   닫은 뒤에는 이 스크립트가 접속 사전 확인에서 멈추는 것이 정상이다
   APP02 에서는 이 단계를 실행하지 않는다. APP02 는 DB 질의로 적용 여부만 확인한다
EOF
