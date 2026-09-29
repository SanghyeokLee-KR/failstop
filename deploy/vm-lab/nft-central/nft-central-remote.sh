#!/usr/bin/env bash
# nft-central.ps1 이 ssh 로 VM 에 보내 root 로 실행하는 부분이다. 사람이 직접 실행하지 않는다.
# 작업 PC 가 이 파일 앞에 NC_* 변수를, 뒤에 저장소 원본(base64)과 nc_main 호출을 붙여 보낸다.
# '@이름 값' 꼴의 줄은 작업 PC 가 읽는 값이고 나머지 줄(diff 의 '@@' 포함)은 화면에 그대로 나간다.
set -euo pipefail
shopt -s nullglob
export PATH=/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C.UTF-8

ETC=/etc/vm-lab
BACKUP_ROOT=/var/lib/vm-lab/nft-backup
LOG=/var/log/vm-lab/nft-central.log
STAGE=

die() { printf '오류: %s\n' "$*"; exit 2; }

log_event() {  # log_event <action> <key=value ...>
    printf '%s id=%s action=%s %s\n' "$(date -Is)" "$NC_ID" "$1" "$2" >> "$LOG"
}

show_diff() {  # diff 는 차이가 있으면 1 을 돌려준다. 2 는 실패이므로 그대로 멈춘다.
    diff -u "$@" || [[ $? == 1 ]]
}

nc_begin() {
    STAGE=$(mktemp -d /var/lib/vm-lab/nft-central-stage.XXXXXX)
    trap 'rm -rf -- "$STAGE"' EXIT
    mkdir "$STAGE/firewall.d"
}

nc_put() {  # nc_put <상대 경로>. 표준 입력은 base64 다.
    [[ $1 == firewall.nft || $1 =~ ^firewall\.d/[A-Za-z0-9._-]+\.nft$ ]] || die "받지 않는 파일 이름이다: $1"
    base64 -d > "$STAGE/$1"
}

print_manifest() {  # print_manifest <디렉터리>. 형식은 '@f <sha256> <상대 경로>' 다.
    local dir=$1 f
    for f in "$dir/firewall.nft" "$dir"/firewall.d/*.nft; do
        [[ -f $f ]] || continue
        printf '@f %s %s\n' "$(sha256sum < "$f" | cut -d' ' -f1)" "${f#"$dir"/}"
    done
}

count_rules() {
    python3 -c 'import json, sys; print(sum(1 for o in json.load(sys.stdin)["nftables"] if "rule" in o))'
}

# 원본 디렉터리를 /etc/vm-lab 자리에 겹쳐 보이게 한 개인 마운트 공간에서 명령을 실행한다.
# 호스트의 파일은 그대로다. --net 을 더하면 빈 네트워크 공간이라 규칙을 실제로 올려 볼 수 있다.
in_view() {  # in_view <원본 디렉터리> <명령> [unshare 인자...]
    local dir=$1 cmd=$2
    shift 2
    unshare --mount "$@" -- sh -c 'mount --bind "$1/firewall.nft" /etc/vm-lab/firewall.nft &&
        mount --bind "$1/firewall.d" /etc/vm-lab/firewall.d && eval "$2"' sh "$dir" "$cmd" < /dev/null
}
check_syntax() { in_view "$1" 'nft -c -f /etc/vm-lab/firewall.nft'; }
render_text() { in_view "$1" 'nft -f /etc/vm-lab/firewall.nft && nft -s list table inet vmlab' --net; }
render_json() { in_view "$1" 'nft -f /etc/vm-lab/firewall.nft && nft -j list table inet vmlab' --net; }

# 카운터를 뺀 지금 규칙. 파일로 만든 규칙(render_text)과 글자 단위로 비교한다.
live_text() { nft -s list table inet vmlab; }

cmd_pull() {
    local f
    [[ -f $ETC/firewall.nft ]] || die "$ETC/firewall.nft 가 없다."
    # 원본 폴더와 작업 PC 쪽 검사가 받는 이름만 가져온다. 다른 이름의 파일은 VM 에서 먼저 고친다.
    for f in "$ETC"/firewall.d/*.nft; do
        [[ ${f##*/} =~ ^[A-Za-z0-9._-]+\.nft$ ]] || die "이름 규칙에 맞지 않는 규칙 파일이 있다: $f"
    done
    for f in "$ETC/firewall.nft" "$ETC"/firewall.d/*.nft; do
        printf '@file %s %s\n' "${f#"$ETC"/}" "$(base64 -w0 < "$f")"
    done
    for f in "$ETC"/firewall.d/*; do
        [[ $f == *.nft ]] || printf '관리하지 않는 파일(.nft 가 아니다): %s\n' "$f"
    done
}

cmd_audit() {
    local rules total rendered
    print_manifest "$ETC"
    rules=$(nft -j list table inet vmlab | count_rules)
    # OPS01 의 Docker(iptables-nft) 테이블도 전체 수에 들어간다. 그 테이블은 이 도구가 건드리지 않는다.
    total=$(nft -j list ruleset 2> /dev/null | count_rules)
    printf '@rules %s %s\n' "$rules" "$total"
    printf '@svc %s\n' "$(systemctl is-active vm-lab-firewall.service || true)"
    if ! rendered=$(render_text "$ETC" 2>&1); then
        echo '@kernel render-error'
        printf '%s\n' "$rendered"
    elif [[ $rendered == "$(live_text)" ]]; then
        echo '@kernel same'
    else
        echo '@kernel diff'
    fi
    if [[ -f $LOG ]]; then
        printf '@last %s\n' "$(tail -n 1 "$LOG")"
    fi
}

cmd_plan() {
    local rel hash changed=0 added=0 removed=0 out before after
    local -A cur=() new=()
    while read -r _ hash rel; do cur[$rel]=$hash; done < <(print_manifest "$ETC")
    while read -r _ hash rel; do new[$rel]=$hash; done < <(print_manifest "$STAGE")
    # VM 에는 공백이 든 이름의 파일도 있을 수 있어 줄 단위로 읽는다.
    while IFS= read -r rel; do
        if [[ -z ${cur[$rel]:-} ]]; then
            added=$((added + 1))
            echo "추가: $rel"
            show_diff --label /dev/null --label "원본 $rel" /dev/null "$STAGE/$rel"
        elif [[ -z ${new[$rel]:-} ]]; then
            removed=$((removed + 1))
            echo "삭제: $rel"
            show_diff --label "VM $ETC/$rel" --label /dev/null "$ETC/$rel" /dev/null
        elif [[ ${cur[$rel]} != "${new[$rel]}" ]]; then
            changed=$((changed + 1))
            echo "변경: $rel"
            show_diff --label "VM $ETC/$rel" --label "원본 $rel" "$ETC/$rel" "$STAGE/$rel"
        fi
    done < <(printf '%s\n' "${!cur[@]}" "${!new[@]}" | sort -u)
    printf '@files %d %d %d\n' "$changed" "$added" "$removed"
    if ! out=$(check_syntax "$STAGE" 2>&1); then
        echo '@check fail'
        printf '%s\n' "$out"
        return 0
    fi
    echo '@check ok'
    live_text > "$STAGE/live.txt"
    render_text "$STAGE" > "$STAGE/want.txt"
    before=$(nft -j list table inet vmlab | count_rules)
    after=$(render_json "$STAGE" | count_rules)
    printf '@rules %s %s\n' "$before" "$after"
    if cmp -s "$STAGE/live.txt" "$STAGE/want.txt"; then
        echo '@kernel same'
    else
        echo '@kernel diff'
        show_diff --label '커널(지금)' --label '커널(적용 뒤)' "$STAGE/live.txt" "$STAGE/want.txt"
    fi
}

# 타이머가 실행하는 되돌리기. 적용 때마다 백업 디렉터리에 같은 내용으로 쓴다.
write_rollback() {  # write_rollback <백업 디렉터리>
    cat > "$1/rollback.sh" << 'EOF'
#!/usr/bin/env bash
# nft-central 자동 되돌리기. 적용 뒤 작업 PC 의 확인이 오지 않으면 systemd 타이머가 실행한다.
# 인자는 백업 디렉터리다. 그 안의 files/ 를 /etc/vm-lab 에 되돌리고 규칙을 다시 올린다.
set -u
shopt -s nullglob
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
b=$1
# 확인(confirm)과 되돌리기 가운데 decided 를 먼저 만든 쪽만 진행한다. mkdir 는 원자적이다.
mkdir "$b/decided" 2> /dev/null || exit 0
ok=true
rm -f -- /etc/vm-lab/firewall.d/*.nft || ok=false
for f in "$b"/files/firewall.d/*.nft; do cp -p -- "$f" /etc/vm-lab/firewall.d/ || ok=false; done
cp -p -- "$b/files/firewall.nft" /etc/vm-lab/firewall.nft || ok=false
# 라벨 복구가 실패해도 규칙은 되돌린다. 그 실패는 기록의 label= 에 남긴다.
label=ok
restorecon -F /etc/vm-lab/firewall.nft /etc/vm-lab/firewall.d/*.nft || label=fail
if $ok && nft -f /etc/vm-lab/firewall.nft; then result=ok; else result=fail; fi
printf '%s id=%s action=rollback result=%s label=%s\n' "$(date -Is)" "${b##*/}" "$result" "$label" >> /var/log/vm-lab/nft-central.log
[[ $result == ok ]]
EOF
}

install_stage() {
    local f name
    # 원본에 없는 파일부터 지운다. 커널 규칙은 뒤의 nft -f 가 한 번에 바꾸므로 그사이에도 그대로다.
    for f in "$ETC"/firewall.d/*.nft; do
        name=${f##*/}
        [[ -f $STAGE/firewall.d/$name ]] || rm -f -- "$f"
    done
    for f in "$STAGE"/firewall.d/*.nft; do
        name=${f##*/}
        cmp -s "$f" "$ETC/firewall.d/$name" || install -o root -g root -m 0600 "$f" "$ETC/firewall.d/$name"
    done
    cmp -s "$STAGE/firewall.nft" "$ETC/firewall.nft" || install -o root -g root -m 0600 "$STAGE/firewall.nft" "$ETC/firewall.nft"
    restorecon -F "$ETC/firewall.nft" "$ETC"/firewall.d/*.nft
}

cmd_apply() {
    local b=$BACKUP_ROOT/$NC_ID unit=nft-central-rollback-$NC_ID out= ok=true f
    exec 9> /run/lock/nft-central.lock
    flock -n 9 || die '이 VM 에서 다른 적용이 진행 중이다.'
    if [[ -n $(systemctl list-units --plain --no-legend --state=waiting 'nft-central-rollback-*.timer') ||
          -n $(systemctl list-units --plain --no-legend --state=active,activating 'nft-central-rollback-*.service') ]]; then
        die '확인을 기다리거나 되돌리는 중인 이전 적용이 있다. 그 일이 끝난 뒤 다시 실행한다.'
    fi

    # 1. 원본 전체(firewall.nft + 새 firewall.d)를 구문 검사한다. 실패하면 아무것도 바꾸지 않는다.
    if ! out=$(check_syntax "$STAGE" 2>&1); then
        log_event check "result=fail set=$NC_SET by=$NC_BY"
        printf '%s\n' "$out"
        echo '@result check-failed'
        return 0
    fi

    # 2. 지금 파일과 전체 규칙을 남긴다. ruleset.nft 는 사람이 보는 사본이고 되돌리기는 files/ 로 한다.
    install -d -m 0700 "$BACKUP_ROOT"
    # 같은 ID 의 백업이 남아 있으면 그 decided 표시가 확인과 되돌리기를 모두 막으므로 새로 만들 수 있을 때만 진행한다.
    mkdir -m 0700 "$b" || die "같은 ID 의 백업이 이미 있다: $b"
    mkdir -m 0700 "$b/files" "$b/files/firewall.d"
    cp -a -- "$ETC/firewall.nft" "$b/files/"
    for f in "$ETC"/firewall.d/*.nft; do cp -a -- "$f" "$b/files/firewall.d/"; done
    nft list ruleset > "$b/ruleset.nft" 2> /dev/null
    print_manifest "$ETC" > "$b/manifest"
    write_rollback "$b"

    # 3. 되돌리기를 먼저 예약한다. 예약이 서지 않으면 set -e 로 여기서 멈추고 설치하지 않는다.
    systemd-run --quiet --unit="$unit" --on-active="$NC_ROLLBACK_SEC" \
        --timer-property=AccuracySec=1s --timer-property=RemainAfterElapse=no \
        --description="nft-central 자동 되돌리기 $NC_ID" /usr/bin/bash "$b/rollback.sh" "$b"
    printf '@backup %s\n' "$b"
    printf '@rollback-at %s\n' "$(date -d "+$NC_ROLLBACK_SEC sec" '+%F %T')"

    # 4. 설치하고 flush 와 적재를 한 트랜잭션으로 올린다. 이미 맺은 연결의 conntrack 은 그대로라 established 는 끊기지 않는다.
    install_stage
    [[ $(print_manifest "$ETC") == "$(print_manifest "$STAGE")" ]] || { ok=false; out='설치한 파일이 원본과 다르다.'; }
    if $ok && ! out=$(nft -f "$ETC/firewall.nft" 2>&1); then ok=false; fi
    if $ok && [[ $(live_text) != "$(render_text "$STAGE")" ]]; then ok=false; out='올린 규칙이 원본으로 만든 규칙과 다르다.'; fi
    if ! $ok; then
        printf '%s\n' "$out"
        bash "$b/rollback.sh" "$b"
        systemctl stop "$unit.timer"
        echo '@result rolled-back-now'
        return 0
    fi
    log_event apply "result=pending set=$NC_SET by=$NC_BY rollback_in=${NC_ROLLBACK_SEC}s"
    printf '@applied %s\n' "$(date +%s)"
    echo '@result pending'
}

cmd_confirm() {
    local b=$BACKUP_ROOT/$NC_ID
    [[ -d $b ]] || die "적용 기록이 없다: $NC_ID"
    if ! mkdir "$b/decided" 2> /dev/null; then
        echo '@confirm too-late'
        grep -F " id=$NC_ID " "$LOG" || [[ $? == 1 ]]
        return 0
    fi
    # 타이머가 방금 끝나 유닛이 내려갔으면 멈출 것이 없다. 되돌리기가 시작됐더라도 decided 가 있어 아무것도 하지 않는다.
    systemctl stop "nft-central-rollback-$NC_ID.timer" 2> /dev/null ||
        [[ $(systemctl show -p LoadState --value "nft-central-rollback-$NC_ID.timer") == not-found ]]
    log_event confirm "result=ok checks=$NC_CHECKS"
    echo '@confirm ok'
}

# 확인에 실패해 되돌리기를 기다린 뒤 부른다. 적용 전 목록(@before)과 지금 상태를 함께 낸다.
cmd_result() {
    grep -F " id=$NC_ID " "$LOG" || [[ $? == 1 ]]
    sed 's/^@f /@before /' "$BACKUP_ROOT/$NC_ID/manifest"
    cmd_audit
}

nc_main() {
    case $NC_ACTION in
        pull) cmd_pull ;;
        audit) cmd_audit ;;
        plan) cmd_plan ;;
        apply) cmd_apply ;;
        confirm) cmd_confirm ;;
        result) cmd_result ;;
        *) die "모르는 동작이다: $NC_ACTION" ;;
    esac
}
