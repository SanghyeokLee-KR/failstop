"""k6_ceiling 앞의 스택 준비. k6_ceiling.py 는 초기화와 키 발급을 하지 않는다.

roundtrip_probe.main 의 준비 절차를 같은 순서와 같은 환경(ENV)으로 한다.
볼륨 파기와 기동, 키 재발급, 키 대조, 봇 20 OPS, 봇 안정 대기.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scale'))
import clean_suite as cs  # noqa: E402
from roundtrip_probe import ENV  # noqa: E402


def main():
    print('ENV %s' % ENV, flush=True)
    if not cs.reset_stack(ENV):
        print('초기화 실패: %s' % cs.LAST_ERR[0][-300:], flush=True)
        return 1
    n = cs.reissue_keys()
    if n == 0:
        print('키 재발급 실패', flush=True)
        return 1
    hit, want = cs.keys_match_db()
    print('키 대조 %d/%d' % (hit, want), flush=True)
    if hit < want:
        return 1
    cs.set_bot_ops(20)
    if not cs.wait_bots_steady(240):
        return 1
    print('준비 완료. 초기 상태 %s' % cs.db_state(), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
