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

사용:
  python harness/asg/capacity_reconciler.py --lb-conf <경로> --log <경로>
  python harness/asg/capacity_reconciler.py --once        한 주기만 돌고 끝낸다

산출: --log 경로에 JSON Lines. 한 줄이 한 번의 조치다.
"""

import argparse
import json
import sys
import time

import asg_controller as ctl


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
        """지금 조회를 받는 노드. 리더를 포함한 총 대수다. 화면이 세는 수와 같아야 한다."""
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

    def add(self):
        target = next((n for n in ctl.POOL
                       if not (self.lb.active(n) and ctl.running(n))), None)
        if target is None:
            self.emit('add.exhausted', note='설정에 더 쓸 노드가 없다')
            return
        t0 = time.time()
        if self.key is None:
            self.key = ctl.master_key()
        if not ctl.running(target):
            self.emit('add.launch', node=target)
            ctl.launch(target, self.key)
            if not ctl.wait_healthy(target, self.a.launch_budget):
                self.emit('add.timeout', node=target, budget=self.a.launch_budget)
                return
        self.lb.set_down(target, False)
        self.lb.reload()
        self.emit('add.done', node=target, total=len(self.members()),
                  elapsed=round(time.time() - t0, 1))

    def remove(self):
        # 뒤에서부터 뺀다. 리더는 목록에 없으므로 절대 대상이 되지 않는다.
        target = next((n for n in reversed(ctl.POOL)
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
        self.emit('start', interval=self.a.interval, drain=self.a.drain,
                  once=self.a.once, nodes=len(ctl.POOL) + 1)
        end = time.time() + self.a.duration
        while True:
            try:
                self.step()
            except Exception as e:
                # 한 주기의 실패가 조정기를 끝내지 않는다. 다음 주기에 다시 시도한다.
                self.emit('error', message=str(e)[:200])
            if self.a.once or time.time() >= end:
                break
            time.sleep(self.a.interval)
        self.emit('stop')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--lb-conf', required=True, help='nginx.conf 호스트 경로. 바인드 마운트 원본')
    p.add_argument('--lb-container', default='ex-lb')
    p.add_argument('--pg-container', default='ex-pg')
    p.add_argument('--database', default='exchange')
    p.add_argument('--user', default='exchange')
    p.add_argument('--log', required=True)
    p.add_argument('--interval', type=float, default=5)
    p.add_argument('--duration', type=float, default=3600)
    p.add_argument('--drain', type=float, default=15, help='connection draining 창')
    p.add_argument('--launch-budget', type=float, default=90)
    p.add_argument('--node-cpus', type=float, default=2.0, help='새 노드에 줄 vCPU')
    p.add_argument('--once', action='store_true', help='한 주기만 돌고 끝낸다')
    a = p.parse_args()
    # launch() 가 모듈 전역을 읽는다. 조정기가 세우는 노드의 규격을 여기서 정한다.
    ctl.CPUS_PER_NODE = a.node_cpus
    try:
        Reconciler(a).run()
    except KeyboardInterrupt:
        print('중단', file=sys.stderr)


if __name__ == '__main__':
    main()
