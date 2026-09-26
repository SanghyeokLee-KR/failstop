# Patroni 실험 스택의 DB 노드 이미지(Patroni 설계 3-6). 태그는 exchange-patroni-db:local 이다.
# 학교 DB 가 glibc(Amazon Linux)라 musl 인 alpine 보다 정렬 규칙이 가까운 Debian 판을 쓴다.
# 판은 README 의 표와 같아야 한다. 올릴 때 두 곳을 함께 바꾼다.
FROM postgres:17.11-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652

# Patroni 는 전용 가상 환경에 둔다. 배포판 파이썬 패키지와 섞이면 판을 고정할 수 없다(AWS 배치도 /opt/patroni, 설계 9-1).
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/patroni \
    && /opt/patroni/bin/pip install --no-cache-dir \
        "patroni[etcd3,psycopg3]==4.1.5" "psycopg[binary]==3.3.6"

ENV PATH="/opt/patroni/bin:${PATH}"
COPY --chmod=0755 post_bootstrap.sh /patroni/post_bootstrap.sh
COPY patroni.yml /patroni/patroni.yml

USER postgres
# PID 1 은 compose 의 init(tini)이 맡는다(docker-compose.patroni.yml init: true). postmaster 가 PID 1 이면 컨테이너 안의
# 고아가 신호로 끝나기만 해도 크래시 재시작이 나고 커밋을 기다리던 행이 복제 없이 보인다(사전 확인 Q2).
ENTRYPOINT ["patroni", "/patroni/patroni.yml"]
