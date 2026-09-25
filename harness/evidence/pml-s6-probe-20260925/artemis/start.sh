#!/bin/sh
# 폐기용 확인 전용. 인스턴스를 만들고 STOMP 수신기에 /queue/·/topic/ 접두어를 붙인 뒤 띄운다.
set -e
cd /var/lib/artemis-instance
if [ ! -f ./etc/broker.xml ]; then
  /opt/activemq-artemis/bin/artemis create --user probe --password probe --silent --require-login \
    --no-web --no-amqp-acceptor --no-mqtt-acceptor --no-hornetq-acceptor --java-memory "${PROBE_XMX:-128M}" . >/dev/null
  sed -i 's#<acceptor name="stomp">tcp://0.0.0.0:61613?#<acceptor name="stomp">tcp://0.0.0.0:61613?anycastPrefix=/queue/;multicastPrefix=/topic/;#' ./etc/broker.xml
fi
grep -n 'acceptor name="stomp"' ./etc/broker.xml
exec ./bin/artemis run
