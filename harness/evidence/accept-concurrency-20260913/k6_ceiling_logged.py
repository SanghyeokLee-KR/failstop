"""k6_ceiling.main 을 그대로 부르되 k6 출력만 파일로 받는다.

k6_ceiling.main 은 run_k6 를 log_path 없이 불러 출력을 파이프로 받고, 구간 끝까지 읽지 않는다.
calibrate.js 는 접수 1건마다 ACK 를 console.log 로 내므로 요청 600/s 에서 파이프 버퍼가 차면
k6 가 쓰기에서 막힌다(k6_ceiling.run_k6 주석이 적은 위험). roundtrip_probe 와 clean_suite 는
이미 파일로 받는다. 측정 식(measure)과 사다리 절차는 바꾸지 않는다.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / 'scale'))
import k6_ceiling  # noqa: E402

_orig = k6_ceiling.run_k6


def run_k6_logged(rate, duration, run_id, log_path=None):
    return _orig(rate, duration, run_id, log_path=str(HERE / ('k6-%s.log' % run_id)))


k6_ceiling.run_k6 = run_k6_logged


def admin_call_skipped(method, path, body=None):
    # k6_ceiling.main 의 봇 기저 설정은 관리 평면 mTLS 를 타는데 인증서 경로(load_ceiling.PKI)가
    # 이 호스트에 없어 openssl 이 실패한다. 봇 기저는 prep_stack 이 sim_commands 큐로 20 OPS 를 이미 넣었다.
    return ('관리 평면 호출 생략(prep_stack 이 sim_commands 로 %s 설정)' % (body or {}), None)


k6_ceiling.admin_call = admin_call_skipped

if __name__ == '__main__':
    sys.exit(k6_ceiling.main())
