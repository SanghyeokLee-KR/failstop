#!/usr/bin/env bash
set -euo pipefail

# 검증한 기존 앱 JAR를 봇 전용 사용자와 경로에 설치한다. 기동은 별도 단계다.
test "$(id -u)" -eq 0
cd /tmp
printf '%s\n' 'e401a778edacf6e0279229f37dcaebc0619bc81f385a00ca2abb594fbbc5c5ed  exchange-app-3362117.zip' | sha256sum -c -
if ! id exchange-bot >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/exchange-bot --shell /sbin/nologin exchange-bot
fi
install -d -o root -g root -m 0755 /etc/exchange-bot /opt/exchange-bot/releases/3362117
install -d -o exchange-bot -g exchange-bot -m 0750 /var/lib/exchange-bot
test ! -e /opt/exchange-bot/current
test ! -e /etc/systemd/system/exchange-bot.service
unzip -p exchange-app-3362117.zip app.jar > /opt/exchange-bot/releases/3362117/app.jar
chmod 0444 /opt/exchange-bot/releases/3362117/app.jar
ln -s /opt/exchange-bot/releases/3362117 /opt/exchange-bot/current
install -o root -g root -m 0644 /tmp/bot.env.school /etc/exchange-bot/bot.env
install -o root -g root -m 0644 /tmp/exchange-bot.service /etc/systemd/system/exchange-bot.service
install -o root -g root -m 0644 /tmp/capacity_guard.py /opt/exchange-bot/capacity_guard.py
install -o root -g root -m 0644 /tmp/exchange-bot-capacity.service /tmp/exchange-bot-capacity.timer /etc/systemd/system/
test -s /etc/exchange-bot/bot-secret.env
test "$(stat -c '%a:%U:%G' /etc/exchange-bot/bot-secret.env)" = '600:root:root'
sudo -u exchange-bot test -r /etc/exchange-bot/primary.crt
systemd-analyze verify /etc/systemd/system/exchange-bot.service /etc/systemd/system/exchange-bot-capacity.service /etc/systemd/system/exchange-bot-capacity.timer
systemctl daemon-reload
stat -c '%a %U:%G %n' /etc/exchange-bot/* /opt/exchange-bot/current/app.jar
printf '%s\n' '설치 확인 완료. 아직 시작하거나 자동 기동을 켜지 않았다.'
