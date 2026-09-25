"""S5 설계 4-5 의 파티션 LB 설정 초안 대조(폐기용 확인, 저장소와 컨테이너를 바꾸지 않는다).

1. harness/nginx/nginx.conf 의 보존 블록(맵·리졸버·exchange_write·exchange_read, 조회 location, 기본 location)이 초안에
   줄 단위로 그대로 들어 있는지 본다.
2. 초안의 마켓 맵이 V48 의 대응 시드(ADR 0034 결정한 값 3)와 InitialMarketCatalog 의 심볼로 만든 표와 같은지 본다.
구현 단계의 harness/scale/test_nginx_partition_conf.py 가 같은 대조를 unittest 로 옮긴다.
사용: python check_partition_conf.py <nginx.conf> <초안> <InitialMarketCatalog.java> <V48 sql>
"""
import re
import sys


def block(lines, start_pat, depth_open='{', depth_close='}'):
    """start_pat 과 맞는 줄부터 괄호가 닫히는 줄까지(바로 앞 주석 줄 포함)."""
    for i, line in enumerate(lines):
        if re.search(start_pat, line):
            j = i
            while j > 0 and lines[j - 1].strip().startswith('#'):
                j -= 1
            depth = 0
            for k in range(i, len(lines)):
                depth += lines[k].count(depth_open) - lines[k].count(depth_close)
                if depth == 0 and k > i or (depth == 0 and depth_open in lines[i] and depth_close in lines[i]):
                    return lines[j:k + 1]
    raise SystemExit('블록 없음: ' + start_pat)


def main(conf, draft, catalog, v48):
    base = open(conf, encoding='utf-8').read().split('\n')
    new = open(draft, encoding='utf-8').read().split('\n')
    ok = True
    for pat in (r'map \$http_upgrade', r'^\s*resolver ', r'upstream exchange_write', r'upstream exchange_read',
                r'location ~ \^/api/v1/\(markets', r'location / \{'):
        if pat.startswith(r'^\s*resolver'):
            b = [l for l in base if re.search(pat, l)]
            n = [l for l in new if re.search(pat, l)]
            same = b == n
        else:
            b = block(base, pat)
            n = block(new, pat)
            same = b == n
        print('preserved %-40s %s (%d lines)' % (pat, 'SAME' if same else 'DIFF', len(b)))
        ok &= same
    symbols = dict(re.findall(r'new MarketSeed\((\d+), "([A-Z-]+)"', open(catalog, encoding='utf-8').read()))
    sql = open(v48, encoding='utf-8').read()
    p0 = [int(x) for x in re.search(r'WHEN p_market_id IN \(([0-9, ]+)\)\s+THEN \'matching-p0\'', sql).group(1).split(',')]
    p1 = [int(x) for x in re.search(r'WHEN p_market_id IN \(([0-9, ]+)\)\s+THEN \'matching-p1\'', sql).group(1).split(',')]
    expected = {symbols[str(i)]: 'exchange_p0' for i in p0}
    expected.update({symbols[str(i)]: 'exchange_p1' for i in p1})
    m = re.search(r'map \$http_x_exchange_market \$exchange_market_upstream \{(.*?)\n    \}', open(draft, encoding='utf-8').read(),
                  re.S)
    got = dict(re.findall(r'^\s+(KRW-[A-Z]+)\s+(exchange_p[01]);', m.group(1), re.M))
    same_map = got == expected
    print('market map %d entries %s' % (len(got), 'SAME as V48 seed' if same_map else 'DIFF'))
    if not same_map:
        print(' expected', sorted(expected.items()))
        print(' got     ', sorted(got.items()))
    ok &= same_map
    print('RESULT', 'OK' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:5]))
