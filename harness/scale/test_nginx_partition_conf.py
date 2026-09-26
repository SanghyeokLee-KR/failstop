"""파티션 라우팅 LB 설정 시험(ADR 0034 S5, docs/reference/architecture/마켓분할_리더_S5_설계.md 4-5).

harness/nginx/nginx.partition.conf 는 harness/nginx/nginx.conf 를 고치지 않고 따로 둔 파일이다. 여기서 본다.
1. 종전 파일의 보존 블록 여섯(업그레이드 맵, 리졸버, exchange_write, exchange_read, 조회 location, 기본 location)이 줄 단위로 같다.
   힌트 없는 요청과 조회의 기준선(E4·E5·E7)을 바꾸지 않으려는 것이다.
2. 힌트 맵이 V48 의 대응 시드(ADR 0034 결정한 값 3)와 InitialMarketCatalog 의 심볼로 만든 표와 같다.
3. 파티션 업스트림의 모양(1차 max_fails=2 fail_timeout=2s, 백업 max_fails=0)과 주문 쓰기 location, 로그 형식.
4. 조회 증설 조절기(asg_controller.Balancer)가 새 파일에서 chaos-api-3 의 down 만 토글하고 쓰기 구성원을 api-1·api-2 로 읽는다.
nginx -t 와 실제 요청은 폐기용 스택(S5 설계 7-2 Z1)에서 본다. docker 는 부르지 않는다.

돌리는 법:
  python -B -m unittest discover -s harness/scale -p "test_*.py"
"""
import os
import re
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.path.normpath(os.path.join(HERE, '..'))
REPO = os.path.normpath(os.path.join(HARNESS, '..'))
BASE_CONF = os.path.join(HARNESS, 'nginx', 'nginx.conf')
PARTITION_CONF = os.path.join(HARNESS, 'nginx', 'nginx.partition.conf')
CATALOG = os.path.join(REPO, 'server', 'app', 'src', 'main', 'java', 'kr', 'exchange', 'config',
                       'InitialMarketCatalog.java')
V48 = os.path.join(REPO, 'server', 'app', 'src', 'main', 'resources', 'db', 'migration',
                   'V48__market_partition_lease.sql')

sys.path.insert(0, os.path.join(HARNESS, 'asg'))
import asg_controller  # noqa: E402

PRESERVED = (r'map \$http_upgrade', r'upstream exchange_write', r'upstream exchange_read',
             r'location ~ \^/api/v1/\(markets', r'location / \{')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def block(lines, start_pattern):
    """start_pattern 과 맞는 줄부터 중괄호가 닫히는 줄까지. 바로 앞의 주석 줄을 포함한다."""
    for i, line in enumerate(lines):
        if re.search(start_pattern, line):
            j = i
            while j > 0 and lines[j - 1].strip().startswith('#'):
                j -= 1
            depth = 0
            for k in range(i, len(lines)):
                depth += lines[k].count('{') - lines[k].count('}')
                if depth == 0:
                    return lines[j:k + 1]
    raise AssertionError('블록 없음: ' + start_pattern)


def upstream(text, name):
    m = re.search(r'upstream %s \{(.*?)\n    \}' % re.escape(name), text, re.S)
    if not m:
        raise AssertionError('upstream 없음: ' + name)
    return [line.strip() for line in m.group(1).splitlines() if line.strip().startswith('server ')]


def expected_hint_map():
    symbols = dict(re.findall(r'new MarketSeed\((\d+), "([A-Z-]+)"', read(CATALOG)))
    sql = read(V48)
    ids = {}
    for resource in ('p0', 'p1'):
        m = re.search(r"WHEN p_market_id IN \(([0-9, ]+)\)\s+THEN 'matching-%s'" % resource, sql)
        ids[resource] = [int(x) for x in m.group(1).split(',')]
    expected = {}
    for resource, market_ids in ids.items():
        for market_id in market_ids:
            expected[symbols[str(market_id)]] = 'exchange_' + resource
    return expected


class PartitionConfTest(unittest.TestCase):

    def setUp(self):
        self.base = read(BASE_CONF)
        self.partition = read(PARTITION_CONF)

    def test_preserved_blocks_are_line_identical(self):
        base_lines = self.base.split('\n')
        new_lines = self.partition.split('\n')
        for pattern in PRESERVED:
            self.assertEqual(block(base_lines, pattern), block(new_lines, pattern), pattern)
        resolver = [line for line in base_lines if re.search(r'^\s*resolver ', line)]
        self.assertEqual(resolver, [line for line in new_lines if re.search(r'^\s*resolver ', line)])

    def test_hint_map_matches_the_v48_seed_and_the_catalog(self):
        m = re.search(r'map \$http_x_exchange_market \$exchange_market_upstream \{(.*?)\n    \}', self.partition, re.S)
        self.assertIsNotNone(m, '힌트 맵이 없다')
        body = m.group(1)
        self.assertRegex(body, r'\n\s+default\s+exchange_write;', '맵에 없는 힌트가 종전 쓰기 경로로 가지 않는다')
        got = dict(re.findall(r'^\s+(KRW-[A-Z]+)\s+(exchange_p[01]);', body, re.M))
        expected = expected_hint_map()
        self.assertEqual(12, len(expected))
        self.assertEqual(expected, got)

    def test_partition_upstreams_keep_the_primary_for_one_503_and_never_eject_the_backup(self):
        self.assertEqual(['server chaos-api-1:8080 resolve max_fails=2 fail_timeout=2s;',
                          'server chaos-api-2:8080 resolve backup max_fails=0;'], upstream(self.partition, 'exchange_p0'))
        self.assertEqual(['server chaos-api-2:8080 resolve max_fails=2 fail_timeout=2s;',
                          'server chaos-api-1:8080 resolve backup max_fails=0;'], upstream(self.partition, 'exchange_p1'))

    def test_only_order_writes_are_split_by_the_hint(self):
        m = re.search(r'map \$request_method \$exchange_order_upstream \{(.*?)\n    \}', self.partition, re.S)
        self.assertIsNotNone(m)
        rows = dict(re.findall(r'^\s+(\S+)\s+(\S+);', m.group(1), re.M))
        self.assertEqual({'default': 'exchange_write', 'POST': '$exchange_market_upstream',
                          'DELETE': '$exchange_market_upstream'}, rows)
        location = '\n'.join(block(self.partition.split('\n'), r'location ~ \^/api/v1/orders'))
        self.assertIn('location ~ ^/api/v1/orders(/|$) {', location)
        self.assertIn('proxy_pass http://$exchange_order_upstream;', location)
        # 재시도 규칙은 기본 location 과 같다. 선검사 503 은 아무것도 쓰지 않은 거절이라 다른 노드로 넘긴다.
        self.assertIn('proxy_next_upstream error timeout http_502 http_503 non_idempotent;', location)
        self.assertIn('proxy_next_upstream_tries 2;', location)
        # 주문 쓰기 location 이 조회 location 과 기본 location 사이에 있어 조회 경로를 가로채지 않는다.
        order = self.partition.index('location ~ ^/api/v1/orders')
        self.assertLess(self.partition.index('location ~ ^/api/v1/(markets'), order)
        self.assertLess(order, self.partition.index('location / {'))

    def test_access_log_keeps_the_combined_prefix(self):
        m = re.search(r"log_format partition (.*?);", self.partition, re.S)
        self.assertIsNotNone(m)
        fmt = ''.join(re.findall(r"'([^']*)'", m.group(1)))
        self.assertTrue(fmt.startswith('$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent '
                                       '"$http_referer" "$http_user_agent" '), fmt)
        for field in ('ups="$upstream_addr"', 'ust="$upstream_status"', 'hint="$http_x_exchange_market"',
                      'route="$upstream_http_x_exchange_route"', 'rt=$request_time'):
            self.assertIn(field, fmt)
        self.assertIn('access_log /var/log/nginx/access.log partition;', self.partition)

    def test_read_scaler_toggles_only_the_read_node_in_the_partition_file(self):
        tmp = tempfile.mkdtemp()
        try:
            conf = os.path.join(tmp, 'nginx.conf')
            shutil.copyfile(PARTITION_CONF, conf)
            lb = asg_controller.Balancer(conf, 'chaos-lb')
            self.assertEqual({'chaos-api-1', 'chaos-api-2'}, lb.write_members())
            self.assertFalse(lb.active('chaos-api-3'))
            before = read(conf)
            self.assertTrue(lb.set_down('chaos-api-3', False))
            after = read(conf)
            self.assertTrue(lb.active('chaos-api-3'))
            changed = [(a, b) for a, b in zip(before.split('\n'), after.split('\n')) if a != b]
            self.assertEqual(1, len(changed), changed)
            self.assertIn('server chaos-api-3:8080', changed[0][1])
            for name in ('exchange_write', 'exchange_p0', 'exchange_p1'):
                self.assertEqual(upstream(before, name), upstream(after, name), name)
        finally:
            shutil.rmtree(tmp)


if __name__ == '__main__':
    unittest.main()
