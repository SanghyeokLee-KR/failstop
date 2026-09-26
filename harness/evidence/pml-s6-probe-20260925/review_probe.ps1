# S6 설계 검토 대응 확인(R9~R11). 폐기용 컨테이너(pml-probe-*)와 네트워크 pml-probe-net3 만 쓴다.
# 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨에는 접근하지 않는다.
param([string] $Step = 'all')
$ErrorActionPreference = 'Stop'
$ev = 'C:\dev\exchange-pml6\harness\evidence\pml-s6-probe-20260925'
$log = Join-Path $ev 'review_commands.log'
$net = 'pml-probe-net3'
$rmq = 'pml-probe-rmq3'
$img = 'rabbitmq:4.3.6-alpine'
$py = 'python:3.12-alpine'

function Log([string] $s) {
    $line = "[{0}] {1}" -f (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'), $s
    Add-Content -LiteralPath $log -Value $line -Encoding UTF8
    Write-Output $line
}

# 인자 배열로 docker 를 부르고 출력과 종료 코드를 기록한다. 반환은 출력 줄 배열이다.
function Run([string[]] $argv) {
    $shown = ($argv | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    Log "> docker $shown"
    $out = & docker @argv 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    foreach ($o in $out) { Add-Content -LiteralPath $log -Value "  $o" -Encoding UTF8 }
    Log "exit=$code"
    return ,@($out)
}

function Start-Broker {
    Run @('rm', '-f', $rmq) | Out-Null
    Run @('run', '-d', '--name', $rmq, '--network', $net, '--network-alias', 'broker', '--memory', '320m', '--cpus', '2',
        '-e', 'RABBITMQ_DEFAULT_USER=probe', '-e', 'RABBITMQ_DEFAULT_PASS=probe',
        '-e', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2',
        '-v', "$ev\rabbit\enabled_plugins:/etc/rabbitmq/enabled_plugins:ro",
        '-v', "$ev\rabbit\rabbitmq-small.conf:/etc/rabbitmq/rabbitmq.conf:ro", $img) | Out-Null
    # CLI 를 root 로 먼저 부르면 쿠키 파일을 root 소유로 만들어 서버가 eacces 로 죽는다(첫 시도 실측). 기다린 뒤 rabbitmq 사용자로 부른다.
    Start-Sleep -Seconds 6
    for ($i = 0; $i -lt 60; $i++) {
        & docker exec -u rabbitmq $rmq rabbitmq-diagnostics -q check_port_listener 61613 *> $null
        if ($LASTEXITCODE -eq 0) { Log "broker ready after ~$($i*2)s"; return }
        Start-Sleep -Seconds 2
    }
    throw 'broker not ready'
}

function Probe([string[]] $probeArgs, [string] $outFile) {
    $argv = @('run', '--rm', '--network', $net, '-v', "${ev}:/probe:ro", $py, 'python', '-B', '/probe/stomp_probe.py') +
        $probeArgs + @('--host', 'broker', '--login', 'probe', '--passcode', 'probe')
    $out = Run $argv
    $json = @($out | Where-Object { $_ -like '{*' })
    Set-Content -LiteralPath (Join-Path $ev $outFile) -Value $json -Encoding UTF8
}

$adopted = @('--sub-ack', 'client', '--sub-prefetch', '1000', '--sub-max-length', '20000', '--sub-ttl-ms', '10000',
    '--ack-every', '200', '--ack-ms', '200')

if ($Step -in 'all', 'setup') {
    Log '== S6 review probe start'
    Run @('ps', '-a', '--filter', 'name=chaos-', '--format', '{{.Names}}') | Out-Null
    Run @('network', 'create', $net) | Out-Null
    Start-Broker
}

if ($Step -in 'all', 'r9') {
    Log '== R9a adopted subscription, paced 2 publishers x 20000'
    Probe (@('bus', '--count', '20000', '--rate', '2000', '--wait', '60') + $adopted) 'r9a_bus_clientack_paced.json'
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Log '== R9b adopted subscription, burst 2 publishers x 20000'
    Probe (@('bus', '--count', '20000', '--rate', '0', '--wait', '60') + $adopted) 'r9b_bus_clientack_burst.json'
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
}

$final = @('--sub-ack', 'client', '--sub-prefetch', '1000', '--sub-max-length', '5000', '--sub-max-length-bytes', '4194304',
    '--sub-ttl-ms', '10000', '--ack-every', '200', '--ack-ms', '200')

if ($Step -in 'all', 'r9final') {
    # R10b 뒤 설계가 고친 최종 구독(건수 5,000과 바이트 4MiB 상한)으로 R9 를 다시 돈다.
    Log '== R9c final subscription (x-max-length 5000, x-max-length-bytes 4MiB), paced 2 publishers x 20000'
    Probe (@('bus', '--count', '20000', '--rate', '2000', '--wait', '60') + $final) 'r9c_bus_final_paced.json'
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Log '== R9d final subscription, burst 2 publishers x 20000'
    Probe (@('bus', '--count', '20000', '--rate', '0', '--wait', '60') + $final) 'r9d_bus_final_burst.json'
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
}

if ($Step -in 'all', 'r10') {
    Log '== R10 stalled subscriber with adopted subscription'
    Run @('rm', '-f', 'pml-probe-stall3') | Out-Null
    Run @('run', '-d', '--name', 'pml-probe-stall3', '--network', $net, '-v', "${ev}:/probe:ro", $py, 'python', '-B',
        '/probe/stomp_probe.py', 'stall', '--ack', 'client', '--prefetch', '1000', '--max-length', '20000',
        '--ttl-ms', '10000', '--count', '30000', '--hold', '30', '--host', 'broker', '--login', 'probe', '--passcode', 'probe') | Out-Null
    for ($i = 0; $i -lt 90; $i++) {
        $l = (& docker logs pml-probe-stall3 2>&1 | Out-String)
        if ($l -match '"published"') { break }
        Start-Sleep -Seconds 1
    }
    Run @('logs', 'pml-probe-stall3') | Out-Null
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged', 'arguments') | Out-Null
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Start-Sleep -Seconds 13
    Log '-- 13s later (TTL 10s passed)'
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('wait', 'pml-probe-stall3') | Out-Null
    Run @('logs', 'pml-probe-stall3') | Out-Null
    Run @('rm', '-f', 'pml-probe-stall3') | Out-Null
}

if ($Step -in 'all', 'r10b') {
    Log '== R10b stalled subscriber, x-max-length 5000 + x-max-length-bytes 4MiB, body pad 1400 (fresh broker)'
    Start-Broker
    Start-Sleep -Seconds 5
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Run @('rm', '-f', 'pml-probe-stall3b') | Out-Null
    Run @('run', '-d', '--name', 'pml-probe-stall3b', '--network', $net, '-v', "${ev}:/probe:ro", $py, 'python', '-B',
        '/probe/stomp_probe.py', 'stall', '--ack', 'client', '--prefetch', '1000', '--max-length', '5000',
        '--max-length-bytes', '4194304', '--ttl-ms', '10000', '--stall-pad', '1400', '--count', '30000', '--hold', '30',
        '--host', 'broker', '--login', 'probe', '--passcode', 'probe') | Out-Null
    for ($i = 0; $i -lt 90; $i++) {
        $l = (& docker logs pml-probe-stall3b 2>&1 | Out-String)
        if ($l -match '"published"') { break }
        Start-Sleep -Seconds 1
    }
    Run @('logs', 'pml-probe-stall3b') | Out-Null
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged', 'message_bytes', 'arguments') | Out-Null
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Start-Sleep -Seconds 13
    Log '-- 13s later (TTL 10s passed)'
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}}', $rmq) | Out-Null
    Run @('wait', 'pml-probe-stall3b') | Out-Null
    Run @('rm', '-f', 'pml-probe-stall3b') | Out-Null
}

function AlarmRun([string] $mode, [string] $outFile) {
    Start-Broker
    $client = "pml-probe-alarm-$mode"
    Run @('rm', '-f', $client) | Out-Null
    Run (@('run', '-d', '--name', $client, '--network', $net, '-v', "${ev}:/probe:ro", $py, 'python', '-B',
        '/probe/stomp_probe.py', 'alarm', '--rate', '1000', '--body-bytes', '1000', '--hb', '5000', '--hb-interval', '1.0',
        '--write-timeout', '1.0', '--close-mode', $mode, '--duration', '75') + $adopted +
        @('--host', 'broker', '--login', 'probe', '--passcode', 'probe')) | Out-Null
    $toggle = 'sleep 15; date -u +%s > /tmp/alarm.log; rabbitmqctl -q set_vm_memory_high_watermark absolute 1MiB >> /tmp/alarm.log 2>&1; ' +
        'date -u +%s >> /tmp/alarm.log; sleep 25; date -u +%s >> /tmp/alarm.log; ' +
        'rabbitmqctl -q set_vm_memory_high_watermark absolute 192MiB >> /tmp/alarm.log 2>&1; date -u +%s >> /tmp/alarm.log'
    Run @('exec', '-d', '-u', 'rabbitmq', $rmq, 'sh', '-c', $toggle) | Out-Null
    Start-Sleep -Seconds 25
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_connections', 'name', 'state', 'recv_cnt', 'send_cnt', 'send_pend') | Out-Null
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('wait', $client) | Out-Null
    $out = Run @('logs', $client)
    $json = @($out | Where-Object { $_ -like '{*' })
    Set-Content -LiteralPath (Join-Path $ev $outFile) -Value $json -Encoding UTF8
    Run @('exec', '-u', 'rabbitmq', $rmq, 'cat', '/tmp/alarm.log') | Out-Null
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') | Out-Null
    Run @('exec', '-u', 'rabbitmq', $rmq, 'rabbitmq-diagnostics', '-q', 'alarms') | Out-Null
    Run @('rm', '-f', $client) | Out-Null
}

if ($Step -in 'all', 'r11') {
    Log '== R11a memory alarm, publisher closes with RST on write timeout'
    AlarmRun 'rst' 'r11a_alarm_rst.json'
    Log '== R11b memory alarm, publisher closes gracefully on write timeout'
    AlarmRun 'graceful' 'r11b_alarm_graceful.json'
}

if ($Step -in 'all', 'cleanup') {
    Run @('rm', '-f', $rmq) | Out-Null
    Run @('network', 'rm', $net) | Out-Null
    Run @('ps', '-a', '--filter', 'name=pml-probe', '--format', '{{.Names}}') | Out-Null
    Log '== S6 review probe end'
}

