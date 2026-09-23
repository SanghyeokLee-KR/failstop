#!/usr/bin/env python3
"""capacity_reconciler.py. 희망 용량과 실제 노드 수를 맞춘다.

관리 API 가 `capacity_plan.desired_nodes` 를 바꾸고 이 프로그램이 그것을 실현한다.
클라우드에서는 Auto Scaling group 이 하는 일이고 로컬에서는 이것이 대신한다.

`asg_controller.py` 와 무엇이 다른가. 그쪽은 판정과 실행을 모두 한다. CPU 임계를 스스로
보고 스스로 늘린다. ADR 0015 가 그 형태를 기각했다. 판정을 사람이 소유해야 하기 때문이다.
이쪽은 판정을 하지 않는다. 사람이 관리 화면에서 정한 값을 읽어 그대로 맞추기만 한다.
CPU 를 보지 않고 임계도 없다.

두 파일을 합치지 않는 이유. asg_controller 는 2026-09-08 실측의 산물이고 증거가 그 파일을
인용한다(`harness/evidence/asg-scenario-20260908/`). 판정부를 들어내면 그 증거의 재현
경로가 끊긴다. 실행부만 여기서 재사용한다.

멱등하다. 같은 희망값에서 여러 번 돌려도 아무 일이 일어나지 않는다. 한 주기에 한 대만
움직인다. 두 대를 동시에 세우면 그 사이 로드밸런서 재적재가 두 번 겹치고, 실패했을 때
어디까지 갔는지 알기 어렵다.

스택이 둘이다. `ex` 는 2026-09-07 부터 쓰던 수동 스택이고 `chaos` 는 compose 로 뜨는
격리 스택이다. 같은 조정 규칙을 두 곳에 쓰려고 노드 이름·포트·컨테이너를 상수에서 인자로
옮겼다. 기본값이 `ex` 라 종전 호출은 그대로 돈다.

사용:
  python harness/asg/capacity_reconciler.py --lb-conf <경로> --log <경로>
  python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --log <경로>
  python harness/asg/capacity_reconciler.py --once        한 주기만 돌고 끝낸다

산출: --log 경로에 JSON Lines. 한 줄이 한 번의 조치다.
"""

import argparse
import json
import sys
import time

import asg_controller as ctl


# ===== 스택 규격 =====
#
# leader    매칭엔진 보유 노드. 조정 대상이 아니고 항상 구성원으로 센다.
# pool      조정 단위. 앞에서부터 붙이고 뒤에서부터 뗀다.
# min_pool  절대로 빼지 않는 pool 앞머리 대수. chaos 스택의 api-2 는 쓰기 백업이라
#           조회 대수가 줄어도 남아야 한다. 이 칸이 없으면 희망값 1 이 페일오버 전제를 지운다.
# start_mode 노드를 세우는 방법.
#   run    이미지와 인자를 조정기가 직접 조립해 docker run 한다(ex 스택).
#   start  compose 가 만들어 둔 컨테이너를 docker start 한다(chaos 스택). 환경 변수와
#          네트워크를 compose 파일 한 곳에만 두려는 것이다. 조정기가 같은 값을 두 번째로
#          적어 두면 둘이 갈라지는 날 원인을 찾지 못한다.
STACKS = {
    'ex': {
        'leader': 'ex-app',
        'pool': ['ex-app2', 'ex-app3'],
        'host_port': {'ex-app': 8080, 'ex-app2': 8081, 'ex-app3': 8082},
        'lb_container': 'ex-lb',
        'pg_container': 'ex-pg',
        'network': 'exnet',
        'image': 'failstop-app:wslimit',
        'start_mode': 'run',
        'min_pool': 0,
        'node_cpus': 2.0,
    },
    'chaos': {
        'leader': 'chaos-api-1',
        'pool': ['chaos-api-2', 'chaos-api-3'],
        'host_port': {'chaos-api-1': 18080, 'chaos-api-2': 28080, 'chaos-api-3': 38080},
        'lb_container': 'chaos-lb',
        'pg_container': 'chaos-pg',
        'network': 'chaos-net',
        'image': 'exchange-chaos-app:local',
        'start_mode': 'start',
        'min_pool': 1,
        'node_cpus': 1.0,
    },
}


def desired_from_db(container, database, user):
    """희망 대수를 읽는다. 관리 API 가 쓰는 그 표다.

    앱을 거치지 않고 DB 를 직접 읽는 이유가 있다. 조정기는 앱이 죽어 있을 때도 돌아야 한다.
    노드가 전부 내려간 상태에서 관리 API 로 희망값을 물으면 그 API 자체가 응답하지 않는다.
    """
    out = ctl.sh([
        'docker', 'exec', container,
        'psql', '-U', user, '-d', database, '-Atc',
        "SELECT desired_nodes FROM capacity_plan WHERE tier = 'app'",
    ], check=False)
    line = out.strip().splitlines()[0] if out.strip() else ''
    try:
        return int(line)
    except ValueError:
        return None


def exists(name):
    """컨테이너가 만들어져 있는가. 멈춰 있어도 참이다."""
    out = ctl.sh(['docker', 'ps', '-a', '--filter', 'name=^%s$' % name, '--format', '{{.Names}}'],
                 check=False)
    return out.strip() == name


class Reconciler:

    def __init__(self, a):
        self.a = a
        self.lb = ctl.Balancer(a.lb_conf, a.lb_container)
        self.log_f = open(a.log, 'a', encoding='utf-8')
        self.t0 = time.time()
        self.key = None

    def emit(self, kind, **kw):
        rec = {'t': round(time.time() - self.t0, 1), 'kind': kind}
        rec.update(kw)
        self.log_f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        self.log_f.flush()
        print('[%7.1fs] %-16s %s' % (rec['t'], kind,
                                     ' '.join('%s=%s' % (k, v) for k, v in kw.items())), flush=True)

    def members(self):
        """조회 업스트림에 들어 있고 컨테이너가 실행 중인 노드. 리더를 포함한 총 대수다.

        관리 API 의 observed_total 은 액추에이터가 응답하는 노드 수라 정의가 다르다. 켜져 있지만
        업스트림에서 빠진 노드를 그쪽은 세고 이쪽은 세지 않는다.
        """
        return [n for n in [ctl.LEADER] + ctl.POOL
                if self.lb.active(n) and ctl.running(n)]

    def step(self):
        desired = desired_from_db(self.a.pg_container, self.a.database, self.a.user)
        if desired is None:
            self.emit('plan.unreadable', note='capacity_plan 을 읽지 못했다. 이번 주기는 건너뛴다')
            return
        current = len(self.members())
        if current == desired:
            self.emit('steady', desired=desired, current=current)
            return
        if current < desired:
            self.add()
        else:
            self.remove()

    def start_node(self, target):
        """노드를 기동한다. 성공하면 참을 준다. 실패는 호출부가 그대로 기록한다."""
        if self.a.start_mode == 'start':
            # compose 가 만들어 둔 컨테이너를 다시 켠다. 없으면 만들지 않는다. 여기서
            # docker run 으로 대신하면 compose 의 환경 변수를 조정기가 추측해 채우게 된다.
            if not exists(target):
                self.emit('add.absent', node=target,
                          note='컨테이너 미생성. docker compose --profile scale create 필요')
                return False
            ctl.sh(['docker', 'start', target], check=False, timeout=60)
            return True
        if self.key is None:
            self.key = ctl.master_key()
        ctl.launch(target, self.key)
        return True

    def add(self):
        target = next((n for n in ctl.POOL
                       if not (self.lb.active(n) and ctl.running(n))), None)
        if target is None:
            self.emit('add.exhausted', note='설정에 더 쓸 노드가 없다')
            return
        t0 = time.time()
        if not ctl.running(target):
            self.emit('add.launch', node=target, mode=self.a.start_mode)
            if not self.start_node(target):
                return
        # 이미 켜진 노드도 헬스를 본다. 앞 주기에 예산을 넘긴 노드가 다음 주기에 여기로 온다.
        # 실행 중이라는 것만으로 등록하면 조회를 받지 못하는 노드가 업스트림에 들어간다.
        if not ctl.wait_healthy(target, self.a.launch_budget):
            self.emit('add.timeout', node=target, budget=self.a.launch_budget)
            return
        self.lb.set_down(target, False)
        self.lb.reload()
        self.emit('add.done', node=target, total=len(self.members()),
                  elapsed=round(time.time() - t0, 1))

    def remove(self):
        # 뒤에서부터 뺀다. 리더는 목록에 없으므로 절대 대상이 되지 않는다.
        # min_pool 앞머리도 대상에서 뺀다. chaos 스택에서는 그 자리가 쓰기 백업이다.
        removable = ctl.POOL[self.a.min_pool:]
        target = next((n for n in reversed(removable)
                       if self.lb.active(n) and ctl.running(n)), None)
        if target is None:
            self.emit('remove.none', note='뺄 수 있는 조회 노드가 없다')
            return
        t0 = time.time()
        # 1단계. 새 요청을 끊는다. 진행 중 요청은 옛 워커가 마저 처리한다.
        self.lb.set_down(target, True)
        self.lb.reload()
        self.emit('remove.drain', node=target, window=self.a.drain)
        time.sleep(self.a.drain)
        # 2단계. 창이 끝난 뒤에 내린다. 순서를 뒤집으면 옛 워커가 아직 그 노드의 응답을
        # 기다리는 중일 수 있다.
        ctl.sh(['docker', 'stop', target], check=False, timeout=60)
        self.emit('remove.done', node=target, total=len(self.members()),
                  elapsed=round(time.time() - t0, 1))

    def run(self):
        self.emit('start', stack=self.a.stack, interval=self.a.interval, drain=self.a.drain,
                  once=self.a.once, nodes=len(ctl.POOL) + 1, leader=ctl.LEADER,
                  pool=','.join(ctl.POOL), min_pool=self.a.min_pool, mode=self.a.start_mode)
        end = time.time() + self.a.duration
        while True:
            try:
                self.step()
            except Exception as e:
                # 한 주기의 실패가 조정기를 끝내지 않는다. 다음 주기에 다시 시도한다.
                self.emit('error', message=str(e)[:200])
            if self.a.once:
                self.emit('stop', reason='once')
                return
            if time.time() >= end:
                # 시간이 다 되면 조용히 끝나지 않는다. 이 뒤로는 관리 화면에서 희망 대수를
                # 바꿔도 아무도 따라가지 않는다.
                self.emit('stop', reason='duration', duration=self.a.duration,
                          note='--duration 을 채웠다. 이후 희망 대수 변경은 반영되지 않는다')
                return
            time.sleep(self.a.interval)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--stack', choices=sorted(STACKS), default='ex',
                   help='노드 이름·포트·컨테이너 규격 묶음. 개별 인자가 이 값을 덮는다')
    p.add_argument('--lb-conf', required=True, help='nginx.conf 호스트 경로. 바인드 마운트 원본')
    p.add_argument('--lb-container')
    p.add_argument('--pg-container')
    p.add_argument('--leader', help='매칭엔진 보유 노드. 조정 대상이 아니다')
    p.add_argument('--pool', help='조정 단위 노드를 쉼표로 잇는다. 앞에서 붙이고 뒤에서 뗀다')
    p.add_argument('--min-pool', type=int, help='절대로 빼지 않는 pool 앞머리 대수')
    p.add_argument('--start-mode', choices=['run', 'start'],
                   help='run 은 docker run 으로 조립하고 start 는 compose 가 만든 컨테이너를 켠다')
    p.add_argument('--database', default='exchange')
    p.add_argument('--user', default='exchange')
    p.add_argument('--log', required=True)
    p.add_argument('--interval', type=float, default=5)
    p.add_argument('--duration', type=float, default=3600,
                   help='이 초가 지나면 멈춘다. 띄워 두고 잊은 조정기가 다음 실험에 끼지 않게 하는 상한')
    p.add_argument('--drain', type=float, default=15, help='connection draining 창')
    p.add_argument('--launch-budget', type=float, default=90)
    p.add_argument('--node-cpus', type=float, help='새 노드에 줄 vCPU. run 모드에서만 쓴다')
    p.add_argument('--once', action='store_true', help='한 주기만 돌고 끝낸다')
    a = p.parse_args()

    s = STACKS[a.stack]
    for field in ('lb_container', 'pg_container', 'start_mode', 'node_cpus', 'min_pool'):
        if getattr(a, field) is None:
            setattr(a, field, s[field])
    leader = a.leader or s['leader']
    pool = [n.strip() for n in a.pool.split(',') if n.strip()] if a.pool else list(s['pool'])
    if leader in pool:
        p.error('리더는 조정 단위가 아니다. --pool 에서 %s 를 뺀다' % leader)
    if not 0 <= a.min_pool <= len(pool):
        p.error('--min-pool 은 0 과 pool 대수 사이여야 한다')
    # run 모드의 launch() 는 ex 스택 인자(ex-pg 접속, dev 프로파일)를 고정으로 조립하고 같은 이름의
    # 컨테이너를 먼저 지운다. compose 가 만든 노드에 쓰면 그 컨테이너가 DB 에 닿지 못하는 노드로 바뀐다.
    if a.start_mode == 'run' and s['start_mode'] != 'run':
        p.error('--start-mode run 은 ex 스택 전용이다. %s 스택의 노드는 compose 가 만든 컨테이너를 '
                'start 로 켠다' % a.stack)
    # 축소는 pool 뒤에서부터 docker stop 까지 간다. 쓰기 업스트림에도 있는 노드(chaos 의 쓰기 백업)가
    # 그 자리에 오면 조회 증설을 줄이다가 쓰기 페일오버 경로를 끊는다. --pool 순서와 --min-pool 이
    # 그 자리를 정하므로 설정 파일을 읽어 막는다.
    writers = ctl.Balancer(a.lb_conf, a.lb_container).write_members()
    exposed = [n for n in pool[a.min_pool:] if n in writers]
    if exposed:
        p.error('쓰기 업스트림에도 있는 노드가 축소 대상 자리에 있다: %s. --pool 앞머리 %d 대 안에 '
                '두거나 --min-pool 을 늘린다' % (', '.join(exposed), a.min_pool))

    # asg_controller 의 실행부가 모듈 전역을 읽는다. 조정기가 다룰 스택을 여기서 갈아끼운다.
    ctl.LEADER = leader
    ctl.POOL = pool
    ctl.NETWORK = s['network']
    ctl.IMAGE = s['image']
    ctl.CPUS_PER_NODE = a.node_cpus
    host_port = dict(s['host_port'])
    # 규격에 없는 이름을 --pool 로 받으면 healthy() 가 포트를 찾지 못해 영구 미통과가 된다.
    missing = [n for n in [leader] + pool if n not in host_port]
    if missing:
        p.error('호스트 포트를 모르는 노드가 있다: %s' % ', '.join(missing))
    ctl.HOST_PORT = host_port

    try:
        Reconciler(a).run()
    except KeyboardInterrupt:
        print('중단', file=sys.stderr)


if __name__ == '__main__':
    main()
