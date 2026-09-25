"""P3 합본 LB 설정과 P3 오버레이 시험(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 4-9).

harness/nginx/nginx.partition-relay.conf 는 두 원본을 고치지 않고 합친 파일이다. 여기서 본다.
1. 중계 LB(nginx.relay.conf)의 블록(업그레이드 맵, 리졸버, exchange_write·read·any·ws, 호가·티커·/ws/·기본 location)이 줄 단위로
   같다. 조회 location 은 세 파일에서 앞 주석만 다르므로 주석을 뺀 본문을 대조한다.
2. 파티션 LB(nginx.partition.conf)의 파티션 업스트림 둘, 두 맵, 주문 쓰기 location, 로그 형식이 줄 단위로 같다.
3. 힌트 맵이 V48 대응 시드와 같다(test_nginx_partition_conf 의 기대 표를 그대로 쓴다).
4. 접근 로그는 파티션 형식 하나다(ups=시도한 업스트림, ust=상태 목록). 중계 형식(relay)은 없다.
5. location 순서가 조회, 호가·티커, 주문, /ws/, 기본이다.
6. 조회 증설 조절기가 이 파일에서도 chaos-api-3 의 down 만 토글한다.
7. P3 오버레이(docker-compose.p3.yml)가 합본을 마운트하고, 두 앱에 선호 파티션과 Toxiproxy 주소를 지킨 JDBC 주소(ApplicationName
   만 붙임)를 준다. 기본 파일의 주소와 대조한다.
nginx -t 는 폐기용 컨테이너(pml-probe-*)로 따로 돈다(harness/evidence/pml-s7-probe-20260925/ N7). docker 는 부르지 않는다.

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
RELAY_CONF = os.path.join(HARNESS, 'nginx', 'nginx.relay.conf')
PARTITION_CONF = os.path.join(HARNESS, 'nginx', 'nginx.partition.conf')
MERGED_CONF = os.path.join(HARNESS, 'nginx', 'nginx.partition-relay.conf')
BASE_COMPOSE = os.path.join(HARNESS, 'docker-compose.chaos.yml')
P3_COMPOSE = os.path.join(HARNESS, 'docker-compose.p3.yml')

sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HARNESS, 'asg'))
import asg_controller  # noqa: E402
from test_nginx_partition_conf import block, expected_hint_map, read, upstream  # noqa: E402

# 머리 주석에도 블록 이름이 나오므로(예: nginx.relay.conf 5행의 'upstream exchange_ws') 들여쓴 선언 줄에만 맞춘다.
# 조회 location 은 세 파일에서 본문이 같고 앞 주석만 파일마다 다르다(어느 경로가 기본 location 으로 가는지 적은 주석이다). 그래서
# 블록 대조에서 빼고 test_read_location_body_is_the_same_in_both_originals 가 주석을 뺀 본문을 대조한다.
FROM_RELAY = (r'^\s+map \$http_upgrade', r'^\s+upstream exchange_write \{', r'^\s+upstream exchange_read \{',
              r'^\s+upstream exchange_any \{', r'^\s+upstream exchange_ws \{',
              r'^\s+location ~ \^/api/v1/\(orderbook', r'^\s+location /ws/ \{', r'^\s+location / \{')
FROM_PARTITION = (r'^\s+upstream exchange_p0 \{', r'^\s+upstream exchange_p1 \{', r'^\s+map \$http_x_exchange_market',
                  r'^\s+map \$request_method', r'^\s+location ~ \^/api/v1/orders')
READ_LOCATION = r'^\s+location ~ \^/api/v1/\(markets'


class P3ConfTest(unittest.TestCase):

    def setUp(self):
        self.relay = read(RELAY_CONF)
        self.partition = read(PARTITION_CONF)
        self.merged = read(MERGED_CONF)

    def test_relay_blocks_are_line_identical(self):
        relay_lines, merged_lines = self.relay.split('\n'), self.merged.split('\n')
        for pattern in FROM_RELAY:
            self.assertEqual(block(relay_lines, pattern), block(merged_lines, pattern), pattern)
        resolver = [line for line in relay_lines if re.search(r'^\s*resolver ', line)]
        self.assertEqual(resolver, [line for line in merged_lines if re.search(r'^\s*resolver ', line)])

    def test_partition_blocks_are_line_identical(self):
        partition_lines, merged_lines = self.partition.split('\n'), self.merged.split('\n')
        for pattern in FROM_PARTITION:
            self.assertEqual(block(partition_lines, pattern), block(merged_lines, pattern), pattern)
        for name in ('exchange_p0', 'exchange_p1'):
            self.assertEqual(upstream(self.partition, name), upstream(self.merged, name), name)

    def test_read_location_body_is_the_same_in_both_originals(self):
        def body(text):
            return [line for line in block(text.split('\n'), READ_LOCATION) if not line.strip().startswith('#')]
        self.assertEqual(body(self.relay), body(self.partition))
        self.assertEqual(body(self.relay), body(self.merged))

    def test_hint_map_matches_the_v48_seed(self):
        m = re.search(r'map \$http_x_exchange_market \$exchange_market_upstream \{(.*?)\n    \}', self.merged, re.S)
        self.assertIsNotNone(m, '힌트 맵이 없다')
        got = dict(re.findall(r'^\s+(KRW-[A-Z]+)\s+(exchange_p[01]);', m.group(1), re.M))
        self.assertEqual(expected_hint_map(), got)
        self.assertRegex(m.group(1), r'\n\s+default\s+exchange_write;')

    def test_single_partition_access_log(self):
        self.assertEqual(1, len(re.findall(r'^\s*log_format ', self.merged, re.M)), '로그 형식이 하나가 아니다')
        m_merged = re.search(r"log_format partition (.*?);", self.merged, re.S)
        m_partition = re.search(r"log_format partition (.*?);", self.partition, re.S)
        self.assertIsNotNone(m_merged)
        self.assertEqual(m_partition.group(1), m_merged.group(1), '파티션 형식이 원본과 다르다')
        self.assertEqual(['access_log /var/log/nginx/access.log partition;'],
                         [line.strip() for line in self.merged.split('\n') if line.strip().startswith('access_log')])
        self.assertNotIn('log_format relay', self.merged)

    def test_location_order(self):
        order = [self.merged.index(s) for s in ('location ~ ^/api/v1/(markets', 'location ~ ^/api/v1/(orderbook',
                                                'location ~ ^/api/v1/orders', 'location /ws/ {', 'location / {')]
        self.assertEqual(sorted(order), order, '조회, 호가·티커, 주문, /ws/, 기본 location 순서가 아니다')
        self.assertEqual(5, len(re.findall(r'^\s+location ', self.merged, re.M)), 'location 이 다섯이 아니다')

    def test_read_scaler_toggles_only_the_read_node(self):
        tmp = tempfile.mkdtemp()
        try:
            conf = os.path.join(tmp, 'nginx.conf')
            shutil.copyfile(MERGED_CONF, conf)
            lb = asg_controller.Balancer(conf, 'chaos-lb')
            self.assertEqual({'chaos-api-1', 'chaos-api-2'}, lb.write_members())
            before = read(conf)
            self.assertTrue(lb.set_down('chaos-api-3', False))
            after = read(conf)
            changed = [(a, b) for a, b in zip(before.split('\n'), after.split('\n')) if a != b]
            self.assertEqual(1, len(changed), changed)
            self.assertIn('server chaos-api-3:8080', changed[0][1])
            for name in ('exchange_write', 'exchange_p0', 'exchange_p1', 'exchange_any', 'exchange_ws'):
                self.assertEqual(upstream(before, name), upstream(after, name), name)
        finally:
            shutil.rmtree(tmp)


class P3OverlayTest(unittest.TestCase):

    def setUp(self):
        self.base = read(BASE_COMPOSE)
        self.p3 = read(P3_COMPOSE)

    def service(self, text, name):
        m = re.search(r'^  %s:\n(.*?)(?=^  [a-z0-9-]+:\n|\Z)' % re.escape(name), text, re.S | re.M)
        self.assertIsNotNone(m, name)
        return m.group(1)

    def test_lb_mounts_the_merged_conf(self):
        self.assertIn('volumes: ["./nginx/nginx.partition-relay.conf:/etc/nginx/nginx.conf:ro"]',
                      self.service(self.p3, 'chaos-lb'))

    def test_preferred_partitions_and_application_name_keep_the_toxiproxy_address(self):
        for name, preferred in (('chaos-api-1', 'matching-p0'), ('chaos-api-2', 'matching-p1')):
            base_url = re.search(r'SPRING_DATASOURCE_URL: (\S+)', self.service(self.base, name)).group(1)
            self.assertIn('chaos-toxiproxy', base_url, '기본 파일의 %s 주소가 Toxiproxy 가 아니다' % name)
            svc = self.service(self.p3, name)
            self.assertIn('EXCHANGE_LEADER_PARTITION_PREFERRED: %s' % preferred, svc)
            url = re.search(r'SPRING_DATASOURCE_URL: (\S+)', svc).group(1)
            self.assertEqual(base_url + '?ApplicationName=' + name, url, name)

    def test_overlay_adds_no_service_and_no_secret(self):
        services = re.findall(r'^  ([a-z0-9-]+):\n', self.p3, re.M)
        self.assertEqual(['chaos-api-1', 'chaos-api-2', 'chaos-lb'], services)
        self.assertNotIn('${', self.p3, 'P3 오버레이가 환경 변수(비밀 값)를 읽는다')
        self.assertNotRegex(self.p3, r'(?i)password|passcode|secret:')


if __name__ == '__main__':
    unittest.main()
