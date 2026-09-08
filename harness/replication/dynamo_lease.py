#!/usr/bin/env python3
"""dynamo_lease.py. DynamoDB 리스 항목을 한 줄로 읽는다.

리스가 PostgreSQL 밖으로 나가면 psql 로 리스를 볼 수 없다. 장애 실험에서 "갱신이 계속됐는가"
를 눈으로 확인할 최소 도구가 필요하다.

DynamoDB Local 은 서명을 검사하지 않으므로 자리만 채운 Authorization 헤더로 충분하다. 실제
AWS 엔드포인트에는 쓸 수 없고 그래야 한다. 운영 자격 증명을 다루는 도구를 실험용으로 두지 않는다.

사용:
  python harness/replication/dynamo_lease.py --endpoint http://localhost:8000 --cluster exchange-local
"""

import argparse
import json
import urllib.request

DUMMY_AUTH = ('AWS4-HMAC-SHA256 Credential=local/20260101/ap-northeast-2/dynamodb/aws4_request, '
              'SignedHeaders=host;x-amz-date;x-amz-target, Signature=00')


def get_item(endpoint, table, cluster):
    body = json.dumps({
        'TableName': table,
        'Key': {'clusterId': {'S': cluster}},
        'ConsistentRead': True,
    }).encode()
    req = urllib.request.Request(endpoint, data=body, headers={
        'Content-Type': 'application/x-amz-json-1.0',
        'X-Amz-Target': 'DynamoDB_20120810.GetItem',
        'Authorization': DUMMY_AUTH,
    })
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read().decode()).get('Item') or {}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--endpoint', default='http://localhost:8000')
    p.add_argument('--table', default='exchange-leader-lease')
    p.add_argument('--cluster', default='exchange-local')
    p.add_argument('--json', action='store_true')
    a = p.parse_args()

    item = get_item(a.endpoint, a.table, a.cluster)
    flat = {}
    for k, v in item.items():
        flat[k] = v.get('S') if 'S' in v else int(v.get('N', 0))
    if a.json:
        print(json.dumps(flat, ensure_ascii=False))
        return
    print('leaderId=%s fencingToken=%s leaseUntil=%s updatedAt=%s' % (
        flat.get('leaderId'), flat.get('fencingToken'),
        flat.get('leaseUntil'), flat.get('updatedAt')))


if __name__ == '__main__':
    main()
