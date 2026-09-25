"""S7 실행기 배선 시험(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 4-13·6-3).

pwsh 의 구문 분석기([System.Management.Automation.Language.Parser]::ParseFile)로 실행기와 실험 스크립트의 구문 오류가 0 인지 보고,
AST 로 adr0022-stage5-chaos.ps1 의 -Protocol ValidateSet 이 (1, 2, 3)이고 P3 인자가 있는지 본다. 나머지는 원문 대조다. 가드 두 줄이
3 을 받고, -Protocol 3 은 -P3 와만 돌며, -Protocol 3 의 E4 중계 호출이 W6 이고, 상태·포트 조회가 자원을 넘기는지 본다.

실행기는 부르지 않는다. 가드를 지나면 실험마다 compose 프로젝트의 down -v 를 하는데, 같은 이름(exchange-pml)의 폐기용 스택이 다른
작업 트리에서 돌 수 있기 때문이다. pwsh 가 없으면 구문 분석 사례는 건너뛰고 이유를 남긴다.

돌리는 법:
  python -B -m unittest discover -s harness/scale -p "test_*.py"
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.normpath(os.path.join(HERE, '..', 'scripts'))
RUNNER = os.path.join(SCRIPTS, 'adr0022-stage5-chaos.ps1')
LIB = os.path.join(SCRIPTS, 'lib.ps1')
PARSED = ('adr0022-stage5-chaos.ps1', 'lib.ps1', 'e2-db-cut.ps1', 'e4-leader-kill.ps1', 'e6-spike-failover.ps1', 'up.ps1',
          's7-p3-gate.ps1', 's7-p3-demo.ps1', os.path.join('..', 'evidence', 'pml-S7-gate-draft', 'run-gate.ps1'))
PWSH = shutil.which('pwsh')

# 파일마다 구문 오류와 param 블록(이름, ValidateSet 값)을 JSON 으로 낸다.
PARSE_PS = r'''
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$out = [ordered]@{}
foreach ($f in $args) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$tokens, [ref]$errors)
    $params = [ordered]@{}
    if ($ast.ParamBlock) {
        foreach ($p in $ast.ParamBlock.Parameters) {
            $set = @($p.Attributes | Where-Object { $_.TypeName.Name -eq 'ValidateSet' } |
                ForEach-Object { $_.PositionalArguments | ForEach-Object { "$($_.Value)" } })
            $params[$p.Name.VariablePath.UserPath] = [ordered]@{ type = "$($p.StaticType.Name)"; validateSet = $set }
        }
    }
    $out[[System.IO.Path]::GetFileName($f)] = [ordered]@{
        errors = @($errors | ForEach-Object { "L$($_.Extent.StartLineNumber): $($_.Message)" }); params = $params }
}
$out | ConvertTo-Json -Depth 6 -Compress
'''


def read(path):
    with open(path, encoding='utf-8-sig') as fh:
        return fh.read()


def parse_all():
    with tempfile.TemporaryDirectory() as d:
        script = os.path.join(d, 'parse.ps1')
        with open(script, 'w', encoding='utf-8') as fh:
            fh.write(PARSE_PS)
        files = [os.path.join(SCRIPTS, f) for f in PARSED]
        p = subprocess.run([PWSH, '-NoProfile', '-NonInteractive', '-File', script] + files, capture_output=True,
                           text=True, encoding='utf-8', errors='replace', timeout=180)
    if p.returncode != 0:
        raise AssertionError('pwsh 구문 분석 실패(rc=%s): %s' % (p.returncode, p.stderr[-500:]))
    return json.loads(p.stdout.strip().splitlines()[-1])


@unittest.skipIf(PWSH is None, 'pwsh 가 없어 구문 분석 사례를 건너뛴다(S7 설계 6-3)')
class ParserTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.parsed = parse_all()

    def test_no_syntax_errors(self):
        for name in PARSED:
            self.assertEqual([], self.parsed[os.path.basename(name)]['errors'], name)

    def test_protocol_validate_set_is_1_2_3(self):
        params = self.parsed['adr0022-stage5-chaos.ps1']['params']
        self.assertEqual(['1', '2', '3'], params['Protocol']['validateSet'])

    def test_runner_takes_p3_arguments(self):
        params = self.parsed['adr0022-stage5-chaos.ps1']['params']
        for name in ('P3', 'Resource', 'WebObserveUrl', 'WebObserveSec', 'SurvivorLoadOps', 'ConsolidateOn'):
            self.assertIn(name, params)
        self.assertEqual('SwitchParameter', params['P3']['type'])

    def test_failover_scripts_take_resource(self):
        for name in ('e4-leader-kill.ps1', 'e6-spike-failover.ps1', 'e2-db-cut.ps1'):
            self.assertIn('Resource', self.parsed[name]['params'], name)
        self.assertIn('P3', self.parsed['up.ps1']['params'])


class RunnerTextTest(unittest.TestCase):
    """원문 대조(pwsh 없이 돈다)."""

    @classmethod
    def setUpClass(cls):
        cls.runner = read(RUNNER)
        cls.lib = read(LIB)

    def test_guards_accept_protocol_3(self):
        self.assertIn("if ($PartitionAxes -and $Protocol -lt 2) {", self.runner)
        self.assertIn("if (($Relay -or $WsGateAxes) -and $Protocol -lt 2) {", self.runner)
        self.assertNotIn("$Protocol -ne 2)", self.runner, '종전 가드(-ne 2)는 3 을 막는다')
        self.assertIn("if ($Protocol -ge 2 -and ($Global:CHAOS.Project -ne 'exchange-pml' -or $Image -ne 'exchange-chaos-app:pml'))",
                      self.runner, '폐기용 스택 가드가 P3 에도 걸린다')

    def test_protocol_3_and_p3_go_together(self):
        self.assertIn("if ($Protocol -eq 3 -and -not $P3) {", self.runner)
        self.assertIn("if ($P3 -and $Protocol -ne 3) {", self.runner)
        self.assertIn("if ($P3) { $Relay = $true }", self.runner, 'P3 는 중계를 함께 켠다')
        self.assertIn("if (-not $P3 -and (Test-P3OverlayListed)) {", self.runner)

    def test_resource_required_for_failover_experiments_in_p3(self):
        self.assertRegex(self.runner, r"@\('E2', 'E4', 'E5', 'E6', 'E7', 'COLD'\) -contains \$_")
        self.assertIn("if ($Resource) { $expArgs += @('-Resource', $Resource) }", self.runner)

    def test_p3_transition_latches_the_band(self):
        self.assertIn("if ($Protocol -eq 3) { $transitionArgs += '--latch-band' }", self.runner)
        self.assertIn("'transition', '--target', \"$Protocol\"", self.runner)

    def test_e4_relay_judge_is_w6_in_p3(self):
        m = re.search(r"if \(\$Protocol -eq 3\) \{\n\s+# W6\(.*?\n(.*?)\n\s+\} else \{\n(.*?)\n\s+\}", self.runner, re.S)
        self.assertIsNotNone(m, 'E4 중계 판정의 P3 분기가 있다')
        self.assertIn("'--experiment', 'W6'", m.group(1))
        for arg in ("'--victim'", "'--survivor'", "'--affected-markets'", "'--unaffected-markets'", "'--kill-at'"):
            self.assertIn(arg, m.group(1))
        self.assertIn("'--experiment', 'W5'", m.group(2), 'P2 는 종전 W5 다')
        self.assertIn("'NOT RUN(웹 관찰)'", self.runner)

    def test_p3_common_axes_are_wired(self):
        for sub in ("'segment-judge'", "'victim-writes'", "'ack-interval'", "'epoch-sampler'"):
            self.assertIn(sub, self.runner)
        self.assertIn("'FAIL(P3 공통 판정)'", self.runner)
        self.assertIn("'--protocol', \"$Protocol\"", self.runner, '공통 판정에 모드를 넘긴다')
        self.assertIn("if ($Resource) { $commonArgs += @('--resource', $Resource) }", self.runner)

    def test_rejoin_reads_every_follower_series(self):
        self.assertIn('function Get-FollowerStates', self.runner)
        self.assertIn('$states = Get-FollowerStates -Port $port', self.runner)
        self.assertNotIn("$state = Get-AppMetric -Port $port -Pattern 'exchange_follower_catchup_state", self.runner,
                         '첫 계열만 보면 P3 의 다른 파티션 팔로워 상태를 놓친다')

    def test_status_and_port_calls_pass_the_resource(self):
        for name in ('e4-leader-kill.ps1', 'e6-spike-failover.ps1', 'e2-db-cut.ps1'):
            text = read(os.path.join(SCRIPTS, name))
            for line in text.splitlines():
                code = line.split('#', 1)[0]
                if re.search(r'\b(Get-HarnessStatus|Get-LiveNodePort|Get-LeaderContainer)\b', code):
                    self.assertIn('-Resource', code, '%s: %s' % (name, line.strip()))
        body = self.lib[self.lib.index('function Complete-FailoverExperiment'):]
        body = body[:body.index('\nfunction ', 10)]
        self.assertIn('$postPort = Get-LiveNodePort -Resource $leaseResource', body, '사후 run 포트 조회가 자원을 넘긴다')

    def test_e4_p3_epoch_rule(self):
        text = read(os.path.join(SCRIPTS, 'e4-leader-kill.ps1'))
        self.assertIn('Test-P3AcquireOnce -Resource $leaseResource', text)
        self.assertIn('$epochOk = $newEpoch -eq $oldEpoch + 1', text, 'P1·P2 는 종전 그대로 정확히 +1')
        self.assertIn("killed=$target", text, '실행기가 희생 노드를 콘솔에서 읽는다')

    def test_gate_covers_every_s7_experiment(self):
        gate = read(os.path.join(SCRIPTS, 's7-p3-gate.ps1'))
        m = re.search(r"\[ValidateSet\(([^)]*)\)\]\s*\[string\] \$Experiment", gate)
        self.assertIsNotNone(m)
        names = re.findall(r"'([^']+)'", m.group(1))
        expected = ['Q1', 'Q2', 'Q3a', 'Q3b', 'Q4', 'Q5', 'Q6', 'Q7', 'Q8', 'Q8c', 'Q9', 'Q10', 'Q11', 'Q12', 'Q13', 'Q14', 'Q15', 'Q16']
        self.assertEqual(expected, names, 'S7 설계 5-2 의 Q1~Q16(Q3 a·b, Q8 대조 칸)')
        self.assertIn("'transition', '--target', '3', '--latch-band'", gate)
        self.assertIn('Invoke-P3Preflight', gate)
        self.assertIn("Invoke-ComposeQuiet '--profile obs down -v --remove-orphans'", gate, 'down -v 는 프로젝트가 붙은 공용 래퍼로만 한다')
        self.assertIn("if ($Global:CHAOS.Project -ne 'exchange-pml')", gate)
        self.assertIn("-Protocol', '3', '-P3', '-Resource', 'matching-p1'", gate)

    def test_gate_web_serves_the_static_export(self):
        # web/next.config.ts 가 output: "export" 라 next start 는 기동하지 않는다(S7 구현 검토 라운드 1). 산출물을 정적 서버로 낸다.
        gate = read(os.path.join(SCRIPTS, 's7-p3-gate.ps1'))
        body = gate[gate.index('function Start-GateWeb'):]
        body = body[:body.index('\nfunction ', 10)]
        self.assertNotIn("'run', 'start'", body)
        self.assertIn("'harness/scale/web_out_server.py', '--root', $out, '--port', \"$WebPort\"", body)
        self.assertIn("if ($WebPort -ne 3000)", body, 'dev CORS 허용 출처가 http://localhost:3000 하나다')
        self.assertIn('/exchange/KRW-BTC', body, '거래 화면이 열려야 기동으로 본다')
        with open(os.path.normpath(os.path.join(HERE, '..', '..', 'web', 'next.config.ts')), encoding='utf-8') as fh:
            self.assertIn('output: "export"', fh.read(), '정적 export 가 아니게 되면 이 배선을 다시 본다')

    def test_p3_overlay_order(self):
        self.assertIn("$Global:CHAOS_P3_OVERLAYS = 'harness/docker-compose.relay.yml;harness/docker-compose.p3.yml'", self.lib)
        self.assertIn("'nginx\\.partition-relay\\.conf\\|$'", self.lib, 'LB 마운트 원본이 합본인지 본다')


if __name__ == '__main__':
    unittest.main()
