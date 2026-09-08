#!/usr/bin/env python3
"""asg_controller.py. 조회 계층 오토스케일링 컨트롤러.

AWS EC2 Auto Scaling group 이 하는 판단을 로컬 도커에서 그대로 돌린다. 클라우드가 미배포라
ASG 를 실물로 못 쓰므로, 같은 판정 규칙을 같은 지표 위에 얹어 거동을 재현한다.

무엇을 모사하고 무엇을 모사하지 않는가.

  모사한다: 평균 CPU 임계와 지속 시간 판정, 최소·희망·최대 대수, 노드 등록과 해제,
            connection draining, 헬스체크 실패 노드 제외, 죽은 노드 자동 재생성.
  모사하지 않는다: 클라우드 인스턴스 프로비저닝 시간, 쿨다운 이후의 예열, 스팟 회수,
            AZ 균형 재조정. 프로비저닝은 로컬에서 잴 수 없다(증거 scaleout-timing-20260907).

**쓰기 계층은 이 컨트롤러의 대상이 아니다.** 매칭엔진이 단일 라이터라 노드를 늘려도 주문
천장이 오르지 않는다. 2026-09-07 실측에서 1 대에서 2 대로 늘려도 56.8/s 에서 54.3/s 였다.
그래서 확장 축은 조회뿐이고 리더는 고정 구성원으로 둔다. AWS 로 옮기면 조회 타겟그룹에
걸린 ASG 에 해당한다.

시간 압축. AWS 운영값은 Scale-Out 60% 3 분, Scale-In 30% 10 분이다. 그대로 쓰면 한 회차가
20 분을 넘어 관측 창이 실용적이지 않다. 기본값을 1/6 로 압축했고 판정 로직은 같다.
--out-for 와 --in-for 로 실제 값을 그대로 줄 수 있다.

사용:
  python harness/asg/asg_controller.py --duration 900
  python harness/asg/asg_controller.py --out-for 180 --in-for 600   # AWS 운영값 그대로

산출: --log 경로에 JSON Lines. 한 줄이 한 번의 판정 또는 한 번의 조치다.
"""

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ===== 스택 상수. 2026-09-07 부터 쓰는 로컬 스택의 실제 이름이다 =====

NETWORK = 'exnet'
IMAGE = 'failstop-app:wslimit'
LEADER = 'ex-app'          # 매칭엔진 보유. ASG 대상이 아니다
POOL = ['ex-app2', 'ex-app3']   # 조회 전용. 이 둘이 확장 단위다
HOST_PORT = {'ex-app': 8080, 'ex-app2': 8081, 'ex-app3': 8082}
CPUS_PER_NODE = 2.0        # 기본값. --node-cpus 로 덮는다. CPU 퍼센트 정규화 분모다


def sh(args, check=True, timeout=120):
    """명령을 돌리고 stdout 을 준다. 실패를 조용히 넘기지 않는다."""
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError('명령 실패: %s\n%s' % (' '.join(args), (p.stderr or p.stdout).strip()))
    return p.stdout.strip()


def master_key():
    """리더가 쓰는 마스터키를 그대로 물려받는다.

    값이 다르면 봇과 k6 가 발급한 비밀을 거래소가 복호화하지 못해 주문이 전건 실패한다
    (인수인계 20260907 7-1). 값은 로그에 남기지 않는다.
    """
    envs = sh(['docker', 'inspect', LEADER, '--format', '{{range .Config.Env}}{{println .}}{{end}}'])
    for line in envs.splitlines():
        if line.startswith('EXCHANGE_AUTH_MASTER_KEY_BASE64='):
            return line.split('=', 1)[1]
    raise RuntimeError('리더에서 마스터키를 찾지 못했다. ex-app 이 떠 있는지 확인한다')


def cpu_percent(names):
    """노드별 CPU 를 할당량 대비 퍼센트로 준다.

    docker stats 의 CPUPerc 는 코어 수에 비례해 100% 를 넘는다. 2 vCPU 컨테이너가 포화하면
    200% 다. 임계 60% 는 할당량 대비이므로 코어 수로 나눠 정규화한다.
    """
    if not names:
        return {}
    out = sh(['docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}}'] + names,
             check=False)
    res = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) != 2 or not parts[1].endswith('%'):
            continue
        try:
            res[parts[0]] = round(float(parts[1][:-1]) / CPUS_PER_NODE, 1)
        except ValueError:
            continue
    return res


def wait_healthy(name, budget):
    """budget 초 안에 조회를 받을 수 있게 되는지 본다.

    반복 횟수로 기다리면 안 된다. 죽은 노드는 매 회 연결 타임아웃을 다 쓰므로 '120 회 x 0.5 초'
    가 60 초가 아니라 300 초가 된다. 실제로 그 버그로 컨트롤러가 판정을 멈췄다(2026-09-08).
    벽시계로 끊는다.
    """
    end = time.time() + budget
    while time.time() < end:
        if healthy(name):
            return True
        time.sleep(0.5)
    return False


def healthy(name):
    """조회를 받을 수 있는가. readiness 가 아니라 read 그룹을 본다.

    readiness 는 쓰기를 받을 수 있는가를 답하므로 팔로워가 설계상 503 이다. 그것으로
    판정하면 조회 노드가 전부 빠져 분산이 성립하지 않는다(정본 6.11).
    """
    port = HOST_PORT.get(name)
    if port is None:
        return False
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/health/read' % port, timeout=2) as r:
            return r.status == 200
    except (urllib.error.URLError, OSError):
        return False


def running(name):
    out = sh(['docker', 'ps', '--filter', 'name=^%s$' % name, '--format', '{{.Names}}'], check=False)
    return out.strip() == name


def launch(name, key):
    """조회 전용 노드를 띄운다.

    엔진과 flyway 를 끄는 것이 조회 전용의 성립 조건이다. 엔진을 켠 채로 띄우면 기동 시
    리더 승격을 시도하고, 리더가 살아 있으므로 15 초를 소진한 뒤 기동을 거부당한다(정본 6.2).
    """
    sh(['docker', 'rm', '-f', name], check=False)
    sh([
        'docker', 'run', '-d', '--name', name, '--network', NETWORK,
        '--cpus', str(CPUS_PER_NODE), '--memory', '1g',
        '-p', '%d:8080' % HOST_PORT[name],
        '-e', 'EXCHANGE_AUTH_MASTER_KEY_BASE64=%s' % key,
        IMAGE,
        '--spring.profiles.active=dev',
        '--spring.datasource.url=jdbc:postgresql://ex-pg:5432/exchange',
        '--spring.datasource.username=exchange',
        '--spring.datasource.password=exchange',
        '--spring.flyway.enabled=false',
        '--exchange.engine.enabled=false',
        '--exchange.public.rate-limit.capacity=1000000',
    ])


class Balancer:
    """nginx 업스트림에서 노드를 넣고 뺀다.

    노드 줄은 항상 설정에 남겨 두고 `down` 만 토글한다. 지웠다 넣으면 그 사이에 reload 가
    걸릴 때 이름 해석에 실패할 수 있는데, 이 설정은 resolve 를 붙여 그 위험을 이미 없앴다.
    그래도 토글이 더 단순하고 되돌리기 쉽다.
    """

    def __init__(self, conf_path, container):
        self.conf = conf_path
        self.container = container

    def _read(self):
        with open(self.conf, 'r', encoding='utf-8') as f:
            return f.read()

    def _write(self, text):
        # 단일 파일 바인드 마운트다. 새 파일을 만들어 갈아끼우면 컨테이너 안에서 안 보인다.
        # 같은 파일을 잘라 쓴다.
        with open(self.conf, 'w', encoding='utf-8') as f:
            f.write(text)

    def set_down(self, name, down):
        text = self._read()
        pat = re.compile(r'^(\s*server\s+%s:8080[^\n;]*?)(\s+down)?;$' % re.escape(name), re.M)
        m = pat.search(text)
        if not m:
            raise RuntimeError('업스트림에 %s 줄이 없다' % name)
        if bool(m.group(2)) == down:
            return False
        text = pat.sub(lambda mm: mm.group(1) + (' down' if down else '') + ';', text, count=1)
        self._write(text)
        return True

    def reload(self):
        sh(['docker', 'exec', self.container, 'nginx', '-t'])
        sh(['docker', 'exec', self.container, 'nginx', '-s', 'reload'])

    def active(self, name):
        text = self._read()
        m = re.search(r'^\s*server\s+%s:8080[^\n]*$' % re.escape(name), text, re.M)
        return bool(m) and ' down;' not in m.group(0)


class Asg:
    def __init__(self, a):
        self.a = a
        self.lb = Balancer(a.lb_conf, a.lb_container)
        self.key = master_key()
        self.log_f = open(a.log, 'a', encoding='utf-8')
        self.t0 = time.time()
        self.breach_out = None   # 임계 초과가 시작된 시각
        self.breach_in = None
        self.cooldown_until = 0.0
        # AWS ASG 는 Min 이 아니라 Desired 를 유지한다. 헬스체크가 실패한 인스턴스를 버리고
        # 같은 수가 되도록 새로 띄운다. Desired 는 스케일링 정책이 움직이고 그 사이에는 고정이다.
        # 기동 시점의 현재 대수를 Desired 로 삼는다. 이미 늘어난 상태에서 붙어도 그 수를 지킨다.
        self.desired = max(a.min_pool, self.pool_size())

    def emit(self, kind, **kw):
        rec = {'t': round(time.time() - self.t0, 1), 'kind': kind}
        rec.update(kw)
        self.log_f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        self.log_f.flush()
        print('[%7.1fs] %-12s %s' % (rec['t'], kind,
                                     ' '.join('%s=%s' % (k, v) for k, v in kw.items())), flush=True)

    def members(self):
        """조회를 받는 노드. 리더도 조회를 받으므로 포함한다."""
        return [n for n in [LEADER] + POOL if self.lb.active(n) and running(n)]

    def pool_size(self):
        return len([n for n in POOL if self.lb.active(n) and running(n)])

    # ===== 조치 =====

    def scale_out(self):
        target = next((n for n in POOL if not (self.lb.active(n) and running(n))), None)
        if target is None:
            return
        t_start = time.time()
        if not running(target):
            self.emit('launch.begin', node=target)
            launch(target, self.key)
            self.emit('launch.container', node=target, elapsed=round(time.time() - t_start, 1))
            if not wait_healthy(target, self.a.launch_budget):
                self.emit('launch.timeout', node=target, budget=self.a.launch_budget)
                return
            self.emit('launch.healthy', node=target, elapsed=round(time.time() - t_start, 1))
        self.lb.set_down(target, False)
        self.lb.reload()
        self.desired = self.pool_size()
        self.emit('scale_out', node=target, total=len(self.members()), desired=self.desired,
                  elapsed=round(time.time() - t_start, 1))
        self.cooldown_until = time.time() + self.a.cooldown

    def scale_in(self):
        target = next((n for n in reversed(POOL) if self.lb.active(n) and running(n)), None)
        if target is None or self.pool_size() <= self.a.min_pool:
            return
        t_start = time.time()
        # 1단계. 드레이닝 시작. 새 요청은 안 가고 진행 중 요청은 옛 워커가 마저 처리한다.
        self.lb.set_down(target, True)
        self.lb.reload()
        self.emit('drain.begin', node=target, window=self.a.drain)
        time.sleep(self.a.drain)
        # 2단계. 드레이닝 창이 끝난 뒤에 컨테이너를 내린다. 순서를 뒤집으면 옛 워커가
        # 아직 그 노드로 응답을 기다리는 중일 수 있다.
        sh(['docker', 'stop', target], check=False, timeout=60)
        self.desired = self.pool_size()
        self.emit('scale_in', node=target, total=len(self.members()), desired=self.desired,
                  elapsed=round(time.time() - t_start, 1))
        self.cooldown_until = time.time() + self.a.cooldown

    def recover(self, name):
        """헬스체크가 죽었다고 판정한 노드를 되살린다. ASG 의 인스턴스 교체에 해당한다."""
        t_start = time.time()
        self.emit('recover.begin', node=name)
        self.lb.set_down(name, True)
        self.lb.reload()
        launch(name, self.key)
        if not wait_healthy(name, self.a.launch_budget):
            self.emit('recover.timeout', node=name, budget=self.a.launch_budget)
            return
        self.lb.set_down(name, False)
        self.lb.reload()
        self.emit('recover.done', node=name, elapsed=round(time.time() - t_start, 1))
        self.cooldown_until = time.time() + self.a.cooldown

    # ===== 판정 루프 =====

    def run(self):
        a = self.a
        self.emit('start', min_pool=a.min_pool, max_pool=a.max_pool, desired=self.desired,
                  out_pct=a.out_pct, out_for=a.out_for,
                  in_pct=a.in_pct, in_for=a.in_for, drain=a.drain, cooldown=a.cooldown)
        end = time.time() + a.duration
        fail_streak = {}
        while time.time() < end:
            names = [n for n in [LEADER] + POOL if running(n)]
            cpus = cpu_percent(names)
            active = self.members()
            avg = round(sum(cpus.get(n, 0.0) for n in active) / len(active), 1) if active else 0.0

            # 헬스체크. 연속 실패면 ASG 가 교체한다.
            for n in [LEADER] + POOL:
                should_serve = self.lb.active(n)
                ok = healthy(n) if (should_serve or running(n)) else True
                fail_streak[n] = 0 if ok else fail_streak.get(n, 0) + 1
                if fail_streak[n] == a.health_fails:
                    self.emit('unhealthy', node=n, streak=fail_streak[n])
                    if n == LEADER:
                        # 리더는 교체하지 않는다. 승격은 앱의 리스가 정하고 컨트롤러 밖이다.
                        self.emit('leader.down', note='쓰기 중단. 승격은 앱 리스가 정한다')
                    elif self.pool_size() - 1 < self.desired:
                        self.recover(n)
                        fail_streak[n] = 0
                    else:
                        self.lb.set_down(n, True)
                        self.lb.reload()
                        self.emit('deregister', node=n)

            now = time.time()
            if now < self.cooldown_until:
                self.emit('sample', avg=avg, n=len(active), state='cooldown', **cpus)
                time.sleep(a.interval)
                continue

            # 임계 판정. 지속 시간을 채워야 움직인다. 순간 스파이크에 반응하면 비용이 튄다.
            if avg >= a.out_pct and self.pool_size() < a.max_pool:
                self.breach_out = self.breach_out or now
                self.breach_in = None
                held = round(now - self.breach_out, 1)
                self.emit('sample', avg=avg, n=len(active), state='out_hold', held=held, **cpus)
                if held >= a.out_for:
                    self.breach_out = None
                    self.scale_out()
                    continue
            elif avg <= a.in_pct and self.pool_size() > a.min_pool:
                self.breach_in = self.breach_in or now
                self.breach_out = None
                held = round(now - self.breach_in, 1)
                self.emit('sample', avg=avg, n=len(active), state='in_hold', held=held, **cpus)
                if held >= a.in_for:
                    self.breach_in = None
                    self.scale_in()
                    continue
            else:
                self.breach_out = self.breach_in = None
                self.emit('sample', avg=avg, n=len(active), state='steady', **cpus)

            time.sleep(a.interval)
        self.emit('stop')


def main():
    global CPUS_PER_NODE
    p = argparse.ArgumentParser()
    p.add_argument('--lb-conf', required=True, help='nginx.conf 호스트 경로. 바인드 마운트 원본')
    p.add_argument('--lb-container', default='ex-lb')
    p.add_argument('--log', required=True, help='판정 로그 JSONL 경로')
    p.add_argument('--duration', type=float, default=900)
    p.add_argument('--interval', type=float, default=3)
    p.add_argument('--min-pool', type=int, default=1, help='조회 전용 노드 최소 대수. 리더 제외')
    p.add_argument('--max-pool', type=int, default=2, help='조회 전용 노드 최대 대수. 리더 제외')
    p.add_argument('--out-pct', type=float, default=60)
    p.add_argument('--out-for', type=float, default=30, help='AWS 운영값은 180')
    p.add_argument('--in-pct', type=float, default=30)
    p.add_argument('--in-for', type=float, default=90, help='AWS 운영값은 600')
    p.add_argument('--drain', type=float, default=15, help='connection draining 창')
    p.add_argument('--cooldown', type=float, default=20)
    p.add_argument('--health-fails', type=int, default=2)
    p.add_argument('--launch-budget', type=float, default=90,
                   help='새 노드가 헬스를 통과할 때까지 기다리는 벽시계 상한')
    p.add_argument('--node-cpus', type=float, default=CPUS_PER_NODE,
                   help='노드당 vCPU 할당. CPU 퍼센트 정규화 분모이자 신규 노드 --cpus 값')
    a = p.parse_args()
    # docker stats 의 CPUPerc 는 코어 수에 비례하므로 할당량으로 나눠야 임계 비교가 성립한다.
    CPUS_PER_NODE = a.node_cpus
    try:
        Asg(a).run()
    except KeyboardInterrupt:
        print('중단', file=sys.stderr)


if __name__ == '__main__':
    main()
