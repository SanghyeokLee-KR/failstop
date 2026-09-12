#!/usr/bin/env python3
"""failover_rpo_check.py. primary 급사에서 RPO 와 수동 승격 RTO 와 재편입을 한 번에 잰다.

ADR 0024 가 미충족으로 남긴 셋이다. 셋이 한 장애 사건에서 순서대로 나오므로 한 주행으로 묶는다.

  1. RPO   클라이언트가 ACK 받은 행 중 승격한 standby 에 없는 것. 이것이 실제 유실이다.
  2. RTO   primary 를 죽인 시각부터 새 primary 가 쓰기를 받는 시각까지. 사람 판단 시간은 뺀다.
           **자동 페일오버가 아니다.** 이 값은 명령 실행 시간이지 운영 RTO 가 아니다.
  3. 재편입 구 primary 를 새 primary 의 standby 로 되돌리는 시간과 방식.

왜 ACK 기준인가. 유실은 "디스크에서 사라진 것" 이 아니라 "클라이언트가 받았다고 믿는데 없는
것" 이다. primary 볼륨이 살아 있어도 그것을 버리고 standby 를 승격하면 그 차이가 유실이다.

두 조건에서 잰다.

  natural  정상 복제 중 급사. 이 환경에서의 기본 유실량이다.
  lagged   standby 를 잠시 멈춰 지연을 만든 뒤 급사. 지연이 있을 때 유실이 어떻게 커지는지 본다.

lagged 를 함께 재는 이유. natural 에서 0 이 나와도 그것은 이 호스트 이 부하의 값이지 RPO 0 이
아니다. 지연이 곧 유실 창이라는 것을 보이려면 지연을 만든 회차가 있어야 한다.

사용:
  python harness/scale/failover_rpo_check.py --compose <dir> --project pgha --out out.json
"""

import argparse
import io
import json
import subprocess
import sys
import threading
import time

STOP = threading.Event()


def dex(container, args, timeout=60, user=None):
    pre = ['docker', 'exec'] + (['-u', user] if user else []) + [container]
    return subprocess.run(pre + args, capture_output=True, text=True, timeout=timeout)


def psql(container, sql, user, db, timeout=30):
    # -q 가 없으면 psql 이 결과값과 함께 INSERT 0 1 같은 명령 태그를 찍어 파싱이 깨진다.
    p = dex(container, ['psql', '-q', '-U', user, '-d', db, '-t', '-A', '-F', '|', '-c', sql], timeout)
    return p.stdout.strip() if p.returncode == 0 else None


def alive(container):
    p = subprocess.run(['docker', 'inspect', '-f', '{{.State.Running}}', container],
                       capture_output=True, text=True, timeout=30)
    return p.returncode == 0 and p.stdout.strip() == 'true'


def compose(cd, project, env, args, timeout=600):
    return subprocess.run(
        ['docker', 'compose', '-p', project, '--env-file', env, '-f', 'compose.node-data.yml'] + args,
        cwd=cd, capture_output=True, text=True, timeout=timeout)


class Writer(threading.Thread):
    """primary 에 쉬지 않고 쓰고 ACK 받은 id 만 기록한다.

    한 트랜잭션에 한 행이고 RETURNING 으로 받는다. 서버가 값을 돌려줬다는 것이 곧 커밋 성공이다.
    묶어 쓰면 어디까지 ACK 됐는지 경계가 흐려진다.
    """

    def __init__(self, container, user, db):
        super().__init__(daemon=True)
        self.container = container
        self.user = user
        self.db = db
        self.acked = []
        self.errors = 0
        self.first_error = ''

    def run(self):
        n = 0
        while not STOP.is_set():
            n += 1
            p = dex(self.container,
                    ['psql', '-q', '-U', self.user, '-d', self.db, '-t', '-A', '-c',
                     'INSERT INTO rpo_probe(n) VALUES (%d) RETURNING n' % n], timeout=10)
            got = p.stdout.strip()
            if p.returncode == 0 and got.isdigit():
                self.acked.append(int(got))
            else:
                self.errors += 1
                if not self.first_error:
                    self.first_error = (p.stdout + p.stderr).strip()[-200:]
                if self.errors > 50:
                    return


class Noise(threading.Thread):
    """복제가 밀릴 만큼의 WAL 을 만든다.

    프로브만 돌리면 한 행마다 왕복이라 WAL 이 거의 없고, 유실 창이 열리지 않은 상태를 RPO 0
    으로 오독하게 된다. 판정 대상을 만드는 축이다(22절 공허 통과 금지).
    """

    def __init__(self, container, user, db):
        super().__init__(daemon=True)
        self.container = container
        self.user = user
        self.db = db
        self.batches = 0

    def run(self):
        while not STOP.is_set():
            got = psql(self.container,
                       'INSERT INTO rpo_noise SELECT generate_series(1,200000)',
                       self.user, self.db, timeout=120)
            if got is None:
                return
            self.batches += 1


def wait(fn, timeout, label, poll=0.2):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if fn():
                return True
        except Exception:
            pass
        time.sleep(poll)
    print('  대기 실패: %s' % label, flush=True)
    return False


def fresh_stack(cd, project, env, primary, standby, user, db):
    # 재편입 검증 컨테이너는 compose 가 모르므로 남아 있으면 볼륨이 지워지지 않는다.
    subprocess.run(['docker', 'rm', '-f', '%s-rejoined' % project], capture_output=True, timeout=60)
    compose(cd, project, env, ['--profile', 'standby', 'down', '-v'])
    compose(cd, project, env, ['up', '-d', 'db'])
    if not wait(lambda: psql(primary, 'SELECT 1', user, db) == '1', 90, 'primary 기동'):
        raise SystemExit('primary 가 뜨지 않았다')
    # 공식 이미지는 초기화 스크립트를 임시 서버에서 돌리고 그 서버를 한 번 내린다. 그 창에
    # 걸린 DDL 은 소리 없이 실패한다. 만들어진 것을 확인할 때까지 다시 건다(실측).
    if not wait(lambda: psql(primary,
                             "CREATE TABLE IF NOT EXISTS rpo_probe(n bigint primary key); "
                             "CREATE TABLE IF NOT EXISTS rpo_noise(n bigint); "
                             "SELECT to_regclass('rpo_probe') IS NOT NULL "
                             "AND to_regclass('rpo_noise') IS NOT NULL", user, db) == 't',
                60, '프로브 테이블 생성'):
        raise SystemExit('프로브 테이블을 만들지 못했다')
    t0 = time.monotonic()
    compose(cd, project, env, ['--profile', 'standby', 'up', '-d', 'db-standby'])
    if not wait(lambda: (psql(primary, "SELECT state FROM pg_stat_replication", user, db) == 'streaming'),
                180, 'standby streaming'):
        raise SystemExit('복제가 서지 않았다')
    # 판정 대상이 standby 에 실제로 있는지 먼저 증명한다. 없으면 유실 0 이 공허 통과다(22절).
    if not wait(lambda: psql(standby, "SELECT to_regclass('rpo_probe') IS NOT NULL", user, db) == 't',
                60, 'standby 프로브 테이블'):
        raise SystemExit('standby 에 프로브 테이블이 없다. 판정 대상이 없는 주행이다')
    return round(time.monotonic() - t0, 3)


def run_case(case, cd, project, env, primary, standby, user, db, seconds, lag_seconds):
    print('\n===== %s =====' % case, flush=True)
    basebackup_seconds = fresh_stack(cd, project, env, primary, standby, user, db)

    wal0 = psql(primary, 'SELECT pg_current_wal_lsn()', user, db)
    STOP.clear()
    w = Writer(primary, user, db)
    w.start()
    noise = Noise(primary, user, db)
    noise.start()
    time.sleep(seconds)

    if lag_seconds:
        # 지연을 만든다. SIGSTOP 은 프로세스를 얼려 WAL 수신만 멈춘다. 컨테이너를 지우지 않으므로
        # 승격 대상은 그대로 남는다.
        print('  standby 를 %d초 얼려 지연을 만든다' % lag_seconds, flush=True)
        subprocess.run(['docker', 'pause', standby], capture_output=True, timeout=60)
        time.sleep(lag_seconds)

    wal1 = psql(primary, 'SELECT pg_current_wal_lsn()', user, db)

    # docker kill 뒤 unless-stopped 가 컨테이너를 되살리면 구 primary 가 다시 쓰기 가능해진다.
    # 수동 절차의 fencing(2-2)에 해당하는 조작이라 장애 주입 전에 끊는다.
    subprocess.run(['docker', 'update', '--restart=no', primary], capture_output=True, timeout=60)

    # 급사. SIGKILL 이라 정상 종료 경로를 타지 않는다.
    t0 = time.monotonic()
    subprocess.run(['docker', 'kill', '-s', 'KILL', primary], capture_output=True, timeout=60)
    STOP.set()
    w.join(timeout=20)
    last_acked = max(w.acked) if w.acked else 0
    print('  primary 급사. 마지막 ACK n=%d, ACK 총 %d건' % (last_acked, len(w.acked)), flush=True)
    if not w.acked:
        raise SystemExit('ACK 가 0 건이다. 판정 대상이 없다: %s' % w.first_error)

    if lag_seconds:
        subprocess.run(['docker', 'unpause', standby], capture_output=True, timeout=60)

    # 수동 승격. 사람 판단 시간은 재지 않는다. 명령 실행 시간만이다.
    wait(lambda: psql(standby, 'SELECT 1', user, db) == '1', 60, 'standby 응답')
    recv = psql(standby, 'SELECT pg_last_wal_receive_lsn()', user, db)
    # pg_ctl 은 root 실행을 거부한다. docker exec 의 기본 사용자가 root 라 -u 가 필요하다.
    pr = dex(standby, ['pg_ctl', 'promote', '-D', '/var/lib/postgresql/data'],
             timeout=120, user='postgres')
    promoted = wait(lambda: psql(standby, 'SELECT pg_is_in_recovery()', user, db) == 'f',
                    120, 'promote')
    writable = wait(lambda: psql(standby, "INSERT INTO rpo_probe(n) VALUES (-1) RETURNING n",
                                 user, db) == '-1', 60, '쓰기 수용')
    rto = time.monotonic() - t0

    wal_bytes = psql(standby, "SELECT pg_wal_lsn_diff('%s','%s')" % (wal1, wal0), user, db)
    present = psql(standby, 'SELECT COALESCE(max(n),0) FROM rpo_probe WHERE n > 0', user, db)
    present = int(present) if present and present.lstrip('-').isdigit() else 0
    acked_list = ','.join(str(n) for n in sorted(w.acked))
    missing = psql(standby,
                   'SELECT count(*) FROM unnest(ARRAY[%s]::bigint[]) g '
                   'WHERE NOT EXISTS (SELECT 1 FROM rpo_probe p WHERE p.n = g)' % acked_list,
                   user, db)
    lost = int(missing) if missing and missing.isdigit() else -1

    r = {
        'case': case,
        'acked_rows': len(w.acked),
        'last_acked_n': last_acked,
        'max_n_on_promoted': present,
        'lost_rows': lost,
        'promote_rc': pr.returncode,
        'promote_stderr': pr.stderr.strip()[-200:],
        'promote_ok': promoted,
        'writable_after_promote': writable,
        'rto_command_seconds': round(rto, 3),
        'standby_receive_lsn_before_promote': recv,
        'lag_injected_seconds': lag_seconds,
        'standby_build_from_empty_seconds': basebackup_seconds,
        'noise_batches': noise.batches,
        'probe_errors': w.errors,
        'wal_generated_bytes': wal_bytes,
    }
    print('  RPO 유실 %s행, 승격까지 %.3f초(명령 실행 시간)' % (lost, rto), flush=True)
    if not (promoted and writable):
        raise SystemExit('승격이 서지 않았다. 이 회차의 시간은 대기 타임아웃이지 승격 시간이 아니다')
    return r


def docker_run(project, args, image='postgres:17-alpine', network=None, envs=None, timeout=600):
    cmd = ['docker', 'run', '--rm']
    if network:
        cmd += ['--network', network]
    for k, v in (envs or {}).items():
        cmd += ['-e', '%s=%s' % (k, v)]
    cmd += ['-v', '%s_pgdata:/var/lib/postgresql/data' % project, '--entrypoint', 'sh', image, '-c']
    return subprocess.run(cmd + args, capture_output=True, text=True, timeout=timeout)


def rejoin(project, env, standby, user, db, out):
    """구 primary 를 새 primary 의 standby 로 되돌린다.

    승격으로 타임라인이 갈렸으므로 그대로 붙지 않는다. pg_rewind 가 되감고, 안 되면 전량
    베이스백업이다. 어느 쪽이 성립하는지와 몇 초인지가 이 절의 판정이다.

    종료코드 0 으로 끝내지 않는다. 되감은 뒤 실제로 스트리밍이 서는 것까지 확인한다.
    pg_rewind 성공은 데이터 디렉터리를 맞췄다는 뜻이지 복제가 붙었다는 뜻이 아니다.
    """
    print('')
    print('===== 재편입 =====', flush=True)
    cfg = read_env(env)
    repl_user = cfg.get('REPL_USER', 'replicator')
    slot = 'rejoin_1'
    net = '%s_default' % project
    d = {}
    t_all = time.monotonic()

    # 되감기 전제는 대상 클러스터의 값이고 pg_control 에 있다. 급사 직후 값을 먼저 읽는다.
    ctl = docker_run(project, ['gosu postgres pg_controldata -D /var/lib/postgresql/data'],
                     network='none', timeout=120).stdout
    d['wal_log_hints_at_crash'] = ctl_value(ctl, 'wal_log_hints setting')

    d['checksum_version_at_crash'] = ctl_value(ctl, 'Data page checksum version')
    # 되감기 가능 여부는 급사 시점 값으로 판정한다. 크래시 복구 기동에 설정을 주입한 뒤 읽으면
    # 스스로 켠 값을 되읽어 이 판정이 항상 참이 된다.
    d['rewind_possible'] = (d['wal_log_hints_at_crash'] == 'on'
                            or d['checksum_version_at_crash'] not in ('0', ''))

    # pg_rewind 는 대상이 깨끗하게 정지해 있어야 한다. 급사 뒤이므로 크래시 복구를 한 번
    # 돌리고 내린다. 네트워크가 없고 TCP 도 열지 않는다. 구 primary 가 다시 쓰기를 받는 창을
    # 만들지 않기 위해서다(수동 절차 2-2 fencing 과 같은 전제).
    #
    # WAL 보존에 영향을 주는 설정을 전부 그대로 준다. 빠뜨리면 이 기동의 종료 체크포인트가
    # 되감기에 필요한 구간을 회수한다. wal_keep_size 를 빠뜨려 256MB 보존이 무력화된 회차가
    # 있었다(2026-09-13). wal_log_hints 는 기동마다 pg_control 에 다시 쓰이므로 같은 이유다.
    keep = cfg.get('WAL_KEEP_SIZE', '256MB')
    d['wal_keep_size_on_recovery'] = keep
    t0 = time.monotonic()
    p = docker_run(project, ['gosu postgres pg_ctl -D /var/lib/postgresql/data '
                             '-o "-c listen_addresses= -c wal_log_hints=%s -c wal_keep_size=%s" '
                             '-w start '
                             '&& gosu postgres pg_ctl -D /var/lib/postgresql/data -m fast -w stop'
                             % (d['wal_log_hints_at_crash'], keep)],
                   network='none')
    d['clean_shutdown_rc'] = p.returncode
    d['clean_shutdown_seconds'] = round(time.monotonic() - t0, 3)
    if p.returncode != 0:
        d['clean_shutdown_tail'] = (p.stdout + p.stderr).strip()[-400:]

    ctl = docker_run(project, ['gosu postgres pg_controldata -D /var/lib/postgresql/data'],
                     network='none', timeout=120).stdout
    d['wal_log_hints_after_shutdown'] = ctl_value(ctl, 'wal_log_hints setting')
    if not d['rewind_possible']:
        print('  되감기 전제가 없다. 전량 베이스백업으로 간다', flush=True)
        psql(standby, "SELECT pg_create_physical_replication_slot('%s')" % slot, user, db)
        d['fallback'] = basebackup_rejoin(project, net, cfg, repl_user, slot)
        verify_rejoined(project, net, cfg, standby, user, db, d)
        out['rejoin'] = d
        out['rejoin_seconds'] = round(time.monotonic() - t_all, 3)
        return

    # 새 primary 에 슬롯을 만든다. 베이스백업에 슬롯이 딸려오지 않아 승격한 노드에는 없다.
    psql(standby, "SELECT pg_create_physical_replication_slot('%s')" % slot, user, db)

    # 되감기. 소스는 일반 접속이라 슈퍼유저 롤을 쓴다. 복제 롤은 LOGIN REPLICATION 뿐이라
    # pg_rewind 가 요구하는 함수 실행 권한이 없다.
    t0 = time.monotonic()
    p = docker_run(project,
                   ['gosu postgres pg_rewind --target-pgdata=/var/lib/postgresql/data '
                    '--source-server="host=db-standby port=5432 user=%s dbname=%s" -P' % (user, db)],
                   network=net, envs={'PGPASSWORD': cfg.get('DB_PASSWORD', '')}, timeout=900)
    d['rewind_rc'] = p.returncode
    d['rewind_seconds'] = round(time.monotonic() - t0, 3)
    d['rewind_tail'] = (p.stdout + p.stderr).strip()[-400:]
    d.update(rewind_lsn_gap(standby, user, db, d['rewind_tail']))
    if p.returncode != 0:
        print('  되감기 실패 rc=%d. 전량 베이스백업으로 간다' % p.returncode, flush=True)
        d['fallback'] = basebackup_rejoin(project, net, cfg, repl_user, slot)
        verify_rejoined(project, net, cfg, standby, user, db, d)
        out['rejoin'] = d
        out['rejoin_seconds'] = round(time.monotonic() - t_all, 3)
        return

    # pg_rewind 의 -R 은 소스 접속 문자열을 그대로 쓴다. 그 롤에 복제 권한이 없으므로 복제용
    # 접속 문자열을 따로 쓴다. 비밀번호는 파일에 넣지 않고 환경변수로 넘긴다.
    docker_run(project, ['printf "%s' + chr(92) + 'n' + '" "$C1" "$C2" '
                         '>> /var/lib/postgresql/data/postgresql.auto.conf '
                         '&& gosu postgres touch /var/lib/postgresql/data/standby.signal'],
               network='none', timeout=120, envs={
                   'C1': "primary_conninfo = 'host=db-standby port=5432 user=%s "
                         "application_name=rejoined'" % repl_user,
                   'C2': "primary_slot_name = '%s'" % slot})
    verify_rejoined(project, net, cfg, standby, user, db, d)
    out['rejoin'] = d
    out['rejoin_seconds'] = round(time.monotonic() - t_all, 3)


def basebackup_rejoin(project, net, cfg, repl_user, slot):
    """되감기가 성립하지 않을 때의 재편입. 볼륨을 비우고 새 primary 에서 전량을 받는다.

    이 시간이 곧 이중화 공백이다. 그동안 새 primary 가 홀로 돈다.
    """
    t0 = time.monotonic()
    p = docker_run(project,
                   ['rm -rf /var/lib/postgresql/data/* /var/lib/postgresql/data/.[!.]* 2>/dev/null; '
                    'gosu postgres pg_basebackup '
                    '-d "host=db-standby port=5432 user=%s application_name=rejoined" '
                    '-D /var/lib/postgresql/data -Fp -Xs -P -R -S %s '
                    '&& chmod 0700 /var/lib/postgresql/data' % (repl_user, slot)],
                   network=net, envs={'PGPASSWORD': cfg.get('REPL_PASSWORD', '')}, timeout=1800)
    return {'rc': p.returncode,
            'seconds': round(time.monotonic() - t0, 3),
            'tail': (p.stdout + p.stderr).strip()[-300:]}


def verify_rejoined(project, net, cfg, standby, user, db, d):
    """되돌린 노드를 실제로 띄워 스트리밍이 서는지 본다.

    pg_rewind 든 베이스백업이든 종료코드 0 은 파일을 맞췄다는 뜻이지 복제가 붙었다는 뜻이 아니다.
    compose 의 db-standby 는 별도 볼륨을 쓰므로 같은 볼륨에 붙이려면 직접 띄운다. 이 어긋남은
    배선 한계로 기록한다.
    """
    t0 = time.monotonic()
    name = '%s-rejoined' % project
    subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=60)
    subprocess.run(
        ['docker', 'run', '-d', '--name', name, '--network', net,
         '-e', 'PGPASSWORD=%s' % cfg.get('REPL_PASSWORD', ''),
         '-v', '%s_pgdata:/var/lib/postgresql/data' % project,
         '--entrypoint', 'sh', 'postgres:17-alpine', '-c',
         'exec gosu postgres postgres -c hot_standby=on -c wal_log_hints=on'],
        capture_output=True, text=True, timeout=120)
    streaming = wait(
        lambda: psql(standby, "SELECT state FROM pg_stat_replication WHERE application_name='rejoined'",
                     user, db) == 'streaming', 180, '재편입 노드 streaming')
    d['streaming_after_rejoin'] = streaming
    d['stream_wait_seconds'] = round(time.monotonic() - t0, 3)
    d['rejoined_is_in_recovery'] = psql(name, 'SELECT pg_is_in_recovery()', user, db)
    print('  스트리밍 재개=%s, 확인까지 %.3f초' % (streaming, d['stream_wait_seconds']), flush=True)


def rewind_lsn_gap(standby, user, db, tail):
    """되감기 로그에서 분기 지점과 되짚어야 하는 지점을 뽑아 그 간격을 잰다.

    pg_rewind 가 읽어야 하는 구간은 분기 이후가 아니라 마지막 공통 체크포인트부터다. 없어진
    세그먼트가 분기 지점보다 앞에 있는 것이 그 뜻이고, 이 간격이 wal_keep_size 산정 근거다.
    """
    out = {}
    for ln in tail.splitlines():
        if 'diverged at WAL location' in ln:
            out['diverged_lsn'] = ln.split('diverged at WAL location')[1].split()[0]
        if 'previous WAL record at' in ln:
            out['needed_back_to_lsn'] = ln.rsplit('at', 1)[1].strip()
    if 'diverged_lsn' in out and 'needed_back_to_lsn' in out:
        gap = psql(standby, "SELECT pg_wal_lsn_diff('%s','%s')"
                   % (out['diverged_lsn'], out['needed_back_to_lsn']), user, db)
        out['rewind_lookback_bytes'] = gap
    return out


def ctl_value(text, key):
    for ln in text.splitlines():
        if ln.startswith(key):
            return ln.split(':', 1)[1].strip()
    return ''


def read_env(envfile):
    cfg = {}
    for ln in io.open(envfile, encoding='utf-8'):
        ln = ln.strip()
        if ln and not ln.startswith('#') and '=' in ln:
            k, v = ln.split('=', 1)
            cfg[k.strip()] = v.strip()
    return cfg


def main():
    a_ = argparse.ArgumentParser()
    a_.add_argument('--compose', required=True, help='compose.node-data.yml 이 있는 디렉터리')
    a_.add_argument('--env', default='.env')
    a_.add_argument('--project', default='pgha')
    a_.add_argument('--user', default='exchange')
    a_.add_argument('--db', default='exchange')
    a_.add_argument('--seconds', type=int, default=20, help='급사 전 쓰기 시간')
    a_.add_argument('--lag-seconds', type=int, default=8, help='lagged 회차의 지연 폭')
    a_.add_argument('--out', required=True)
    a = a_.parse_args()

    primary = '%s-db-1' % a.project
    standby = '%s-db-standby-1' % a.project
    envpath = a.env if '/' in a.env or '\\' in a.env else (a.compose + '/' + a.env)

    out = {'cases': []}
    for case, lag in (('natural', 0), ('lagged', a.lag_seconds)):
        out['cases'].append(run_case(case, a.compose, a.project, envpath, primary, standby,
                                     a.user, a.db, a.seconds, lag))
    rejoin(a.project, envpath, standby, a.user, a.db, out)

    io.open(a.out, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, indent=2))
    print('\n-> %s' % a.out, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
