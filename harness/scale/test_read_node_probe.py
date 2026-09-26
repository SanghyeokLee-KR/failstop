"""read_node_probe.py 의 순수 함수 시험(스택 없이 돈다)."""
import unittest

import read_node_probe as rp

IPMAP = rp.merge_ipmap([
    {'map': {'172.20.0.5': 'chaos-api-1', '172.20.0.6': 'chaos-api-2', '172.20.0.9': 'chaos-api-3'}},
    {'map': {'172.20.0.12': 'chaos-api-3'}},
])


def line(method='GET', uri='/api/v1/markets', status=200, ua='pml-app3-probe/R2', ups='172.20.0.9:8080', ust='200'):
    return ('172.20.0.1 - - [26/Sep/2026:05:01:02 +0000] "%s %s HTTP/1.1" %d 123 "-" "%s" ups="%s" ust="%s" hint="-" '
            'route="-" rt=0.004') % (method, uri, status, ua, ups, ust)


class ParseTest(unittest.TestCase):
    def test_parse_partition_line(self):
        r = rp.parse_lb_line(line(uri='/api/v1/trades?market=KRW-BTC&count=3'))
        self.assertEqual(r['method'], 'GET')
        self.assertEqual(r['path'], '/api/v1/trades')
        self.assertEqual(r['status'], 200)
        self.assertEqual(r['tried'], ['172.20.0.9:8080'])
        self.assertEqual(rp.phase_of(r['ua']), 'R2')

    def test_retry_and_internal_redirect_addresses(self):
        self.assertEqual(rp.upstream_addrs('172.20.0.9:8080, 172.20.0.5:8080'), ['172.20.0.9:8080', '172.20.0.5:8080'])
        self.assertEqual(rp.upstream_addrs('172.20.0.9:8080 : 172.20.0.5:8080'), ['172.20.0.9:8080', '172.20.0.5:8080'])
        self.assertEqual(rp.upstream_addrs('-'), [])

    def test_not_partition_format_is_none(self):
        self.assertIsNone(rp.parse_lb_line('2026/09/26 05:01:02 [notice] 1#1: signal process started'))

    def test_node_of_unknown_and_merged_ips(self):
        self.assertEqual(rp.node_of('172.20.0.12:8080', IPMAP), 'chaos-api-3')
        self.assertEqual(rp.node_of('172.20.0.99:8080', IPMAP), 'unknown:172.20.0.99')
        amb = rp.merge_ipmap([{'map': {'10.0.0.1': 'a'}}, {'map': {'10.0.0.1': 'b'}}])
        self.assertEqual(rp.node_of('10.0.0.1:8080', amb), 'ambiguous:10.0.0.1')


class SplitTest(unittest.TestCase):
    def test_served_by_last_address_and_tried(self):
        rows = [rp.parse_lb_line(line(ups='172.20.0.9:8080, 172.20.0.5:8080', ust='502, 200')),
                rp.parse_lb_line(line(ups='172.20.0.6:8080')),
                rp.parse_lb_line(line(ups='172.20.0.9:8080')),
                rp.parse_lb_line(line(ups='172.20.0.9:8080', status=503, ust='503'))]
        s = rp.lb_split(rows, IPMAP)
        self.assertEqual(s['served'], {'chaos-api-1': 1, 'chaos-api-2': 1, 'chaos-api-3': 2})
        self.assertEqual(s['tried_api3'], 3)
        self.assertEqual(s['api3_5xx'], 1)
        self.assertEqual(s['api3_share'], 0.5)

    def test_empty_split_share_is_none(self):
        self.assertIsNone(rp.lb_split([], IPMAP)['api3_share'])

    def test_write_key(self):
        self.assertEqual(rp.write_key(rp.parse_lb_line(line('POST', '/api/v1/orders'))), 'POST /api/v1/orders')
        self.assertEqual(rp.write_key(rp.parse_lb_line(line('DELETE', '/api/v1/orders/123'))), 'DELETE /api/v1/orders')
        self.assertEqual(rp.write_key(rp.parse_lb_line(line('DELETE', '/api/v1/transfers/abc'))), 'DELETE /api/v1/transfers')
        self.assertEqual(rp.write_key(rp.parse_lb_line(line('POST', '/api/v1/auth/login'))), 'POST other')
        self.assertIsNone(rp.write_key(rp.parse_lb_line(line('GET', '/api/v1/orders'))))
        # 조회 location 의 조회 밖 메서드는 쓰기로 세지 않는다(LB 가 조회 업스트림으로 보내는 경로다).
        self.assertIsNone(rp.write_key(rp.parse_lb_line(line('POST', '/api/v1/markets'))))
        self.assertEqual(rp.write_key(rp.parse_lb_line(line('POST', '/api/v1/ordersx'))), 'POST other')


class ClientTest(unittest.TestCase):
    def test_percentile_nearest_rank(self):
        self.assertEqual(rp.percentile(list(range(1, 101)), 95), 95)
        self.assertEqual(rp.percentile([5], 95), 5)
        self.assertIsNone(rp.percentile([], 95))

    def test_summary_counts_transport_as_error(self):
        recs = [(0.0, 'R2', 0, 200, 10.0, None), (1.0, 'R2', 1, 429, 2.0, None), (2.0, 'R2', 2, 0, 8000.0, 'timeout')]
        s = rp.summarize_client(recs)
        self.assertEqual(s['requests'], 3)
        self.assertEqual(s['errors'], 2)
        self.assertEqual(s['errors_by'], {'429': 1, 'transport:timeout': 1})
        self.assertEqual(s['max_ms'], 8000.0)


class NginxTest(unittest.TestCase):
    CONF = """
    upstream exchange_read { zone z 64k; least_conn;
        server chaos-api-1:8080 resolve max_fails=1 fail_timeout=2s;
        server chaos-api-2:8080 resolve max_fails=1 fail_timeout=2s;
        # 주석 server chaos-api-9:8080;
        server chaos-api-3:8080 resolve max_fails=1 fail_timeout=2s down;
    }
    upstream exchange_ws { server chaos-api-1:8080 resolve; server chaos-api-2:8080 resolve; }
    server { location /ws/ { proxy_pass http://exchange_ws; } }
    """

    def test_upstreams_and_ws_location(self):
        b = rp.parse_nginx_upstreams(self.CONF)
        self.assertEqual(b['exchange_read'], ['chaos-api-1', 'chaos-api-2', 'chaos-api-3'])
        self.assertEqual(rp.ws_location_upstream(self.CONF), 'exchange_ws')

    def test_real_partition_relay_conf(self):
        text = (rp.HERE.parent / 'nginx' / 'nginx.partition-relay.conf').read_text(encoding='utf-8')
        b = rp.parse_nginx_upstreams(text)
        self.assertEqual(sorted(n for n, names in b.items() if rp.API3 in names), ['exchange_read'])
        self.assertEqual(b['exchange_ws'], ['chaos-api-1', 'chaos-api-2'])
        self.assertEqual(b['exchange_any'], ['chaos-api-1', 'chaos-api-2'])
        self.assertEqual(rp.ws_location_upstream(text), 'exchange_ws')


def good_facts():
    split = {'lines': 90, 'served': {'chaos-api-1': 30, 'chaos-api-2': 30, 'chaos-api-3': 30}, 'tried_api3': 30, 'api3_share': 0.3333}
    load = {'lines': 20000, 'served': {'chaos-api-1': 7000, 'chaos-api-2': 7000, 'chaos-api-3': 6000}, 'api3_share': 0.3}
    phases = {'R1-markets': split, 'R1-trades': split, 'R3-markets': split, 'R3-trades': split,
              'R1-orderbook': {'lines': 60, 'served': {'chaos-api-1': 60}, 'tried_api3': 0, 'api3_share': 0.0},
              'R2': load, 'R2b': load,
              'C1-out': {'lines': 3000, 'served': {'chaos-api-1': 1500, 'chaos-api-2': 1500}, 'api3_share': 0.0},
              'C1-post': load, 'C2-out': {'lines': 3000, 'served': {'chaos-api-1': 1500, 'chaos-api-2': 1500}, 'api3_share': 0.0},
              'C2-post': load}
    c90 = {'requests': 90, 'errors': 0}
    client = {'R1-markets': c90, 'R1-trades': c90, 'R3-markets': c90, 'R3-trades': c90,
              'R2': {'requests': 40000, 'errors': 0}, 'R2b': {'requests': 9000, 'errors': 0},
              'C1': {'requests': 20000, 'errors': 0}, 'C2': {'requests': 20000, 'errors': 0}}
    probe = {k: {'2xx': 50} for k in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel')}
    probe['errors'] = []
    nf = {'status': 404, 'error': 'not_found'}
    return {
        'preconditions': {'guard_ok': True, 'q0_preflight_ok': True},
        'client': client,
        'lb': {'phases': phases, 'api3_5xx_total': 0,
               'writes': {'tried_api3': 0, 'by_kind': {k: 500 for k in rp.WRITE_MIN}},
               'ws': {'lines': 30, 'tried_api3': 0}, 'orderbook_ticker': {'lines': 900, 'tried_api3': 0}},
        'write_probe': probe,
        'direct_api3': {'targets_prepared': True, 'side_effect_unchanged': True,
                        'writes': {k: dict(nf) for k in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel')},
                        'reads': {'ws_upgrade': {'status': 404}, 'orderbook': {'status': 404}, 'ticker': {'status': 404}}},
        'cycles': {'C1': {'ok': True, 'health_s': 40.0}, 'C2': {'ok': True, 'health_s': 42.0}},
        'ws': {'lb_static': {'exchange_ws': ['chaos-api-1', 'chaos-api-2'], 'exchange_any': ['chaos-api-1', 'chaos-api-2'],
                             'api3_blocks': ['exchange_read'], 'ws_location_upstream': 'exchange_ws'},
               'handshakes': [{'status': 101}] * 30},
    }


POST = {'gated_clean': True, 'summary_ok': True}


class JudgeTest(unittest.TestCase):
    def test_all_good_passes(self):
        v = rp.judge(good_facts(), POST)
        self.assertEqual(v['verdict'], 'PASS', v['failed_axes'] + v['missing_positive'])

    def test_precondition_blocks(self):
        f = good_facts()
        f['preconditions']['q0_preflight_ok'] = False
        v = rp.judge(f, POST)
        self.assertEqual(v['verdict'], 'BLOCKED')
        self.assertEqual(v['blocked_by'], ['q0_preflight_ok'])

    def test_empty_preconditions_block(self):
        f = good_facts()
        f['preconditions'] = {}
        self.assertEqual(rp.judge(f, POST)['verdict'], 'BLOCKED')

    def test_api3_share_below_threshold_fails(self):
        f = good_facts()
        f['lb']['phases']['R2'] = {'lines': 20000, 'served': {'chaos-api-1': 10000, 'chaos-api-2': 9000, 'chaos-api-3': 1000},
                                   'api3_share': 0.05}
        v = rp.judge(f, POST)
        self.assertEqual(v['groups']['A_read_split']['verdict'], 'FAIL')
        self.assertIn('A_read_split.R2_api3_share_ge_0.15', v['failed_axes'])

    def test_errors_fail_and_missing_client_is_not_pass(self):
        f = good_facts()
        f['client']['C2'] = {'requests': 20000, 'errors': 3}
        self.assertEqual(rp.judge(f, POST)['groups']['C_stop_return']['verdict'], 'FAIL')
        f = good_facts()
        del f['client']['C1']
        self.assertEqual(rp.judge(f, POST)['groups']['C_stop_return']['verdict'], 'NOT RUN')

    def test_write_to_api3_fails(self):
        f = good_facts()
        f['lb']['writes']['tried_api3'] = 1
        self.assertEqual(rp.judge(f, POST)['groups']['B_writes']['verdict'], 'FAIL')

    def test_direct_write_not_404_fails(self):
        f = good_facts()
        f['direct_api3']['writes']['withdraw_create'] = {'status': 201, 'error': None}
        self.assertEqual(rp.judge(f, POST)['groups']['B_writes']['verdict'], 'FAIL')

    def test_too_few_writes_is_not_run(self):
        f = good_facts()
        f['lb']['writes']['by_kind']['POST /api/v1/transfers'] = 3
        self.assertEqual(rp.judge(f, POST)['groups']['B_writes']['verdict'], 'NOT RUN')

    def test_out_window_served_by_api3_fails(self):
        f = good_facts()
        f['lb']['phases']['C1-out'] = {'lines': 3000, 'served': {'chaos-api-1': 1500, 'chaos-api-3': 1500}, 'api3_share': 0.5}
        self.assertEqual(rp.judge(f, POST)['groups']['C_stop_return']['verdict'], 'FAIL')

    def test_ws_to_api3_fails(self):
        f = good_facts()
        f['lb']['ws']['tried_api3'] = 2
        self.assertEqual(rp.judge(f, POST)['groups']['D_ws']['verdict'], 'FAIL')
        f = good_facts()
        f['ws']['handshakes'] = [{'status': 101}] * 29 + [{'status': 503}]
        self.assertEqual(rp.judge(f, POST)['groups']['D_ws']['verdict'], 'FAIL')

    def test_missing_post_run_is_not_run(self):
        self.assertEqual(rp.judge(good_facts(), None)['groups']['E_integrity']['verdict'], 'NOT RUN')

    def test_unreadable_value_is_not_true(self):
        f = good_facts()
        f['lb'].pop('api3_5xx_total')
        self.assertEqual(rp.judge(f, POST)['groups']['E_integrity']['verdict'], 'FAIL')


class KeepAliveTest(unittest.TestCase):
    def test_reuses_one_connection(self):
        import http.server
        import threading
        conns = []

        class H(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def setup(self):
                super().setup()
                conns.append(1)

            def do_GET(self):
                body = b'{}'
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        srv = http.server.ThreadingHTTPServer(('localhost', 0), H)
        th = threading.Thread(target=srv.serve_forever, daemon=True)
        th.start()
        try:
            c = rp.KeepAlive(srv.server_address[1], 5)
            got = [c.get('/x', 'ua')[0] for _ in range(20)]
            c.close()
        finally:
            srv.shutdown()
        self.assertEqual(got, [200] * 20)
        self.assertEqual(len(conns), 1)

    def test_transport_failure_is_recorded_not_raised(self):
        c = rp.KeepAlive(1, 1)
        status, err, _ = c.get('/x', 'ua')
        self.assertEqual(status, 0)
        self.assertTrue(err)


if __name__ == '__main__':
    unittest.main()
