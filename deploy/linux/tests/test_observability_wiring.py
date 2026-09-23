"""학교 관측 설정 원본끼리의 연결을 고정한다.

promtool과 amtool은 파일 하나씩의 문법과 규칙 동작을 본다. 이 시험은 그 사이의 연결을 본다. Prometheus가
불러올 규칙 파일, node_exporter가 수집할 유닛과 textfile 디렉터리, 규칙과 대시보드·보고가 조회하는 지표 이름,
Watchdog 전용 경로가 각 원본과 어긋나지 않는지 확인한다. 저장소 파일만 읽으므로 Windows에서도 돈다.
"""
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import unittest

LINUX = Path(__file__).resolve().parents[1]
REPO = LINUX.parents[1]
OBSERVABILITY = LINUX / "observability"
REPORTING = LINUX / "reporting"
BACKUP = LINUX / "postgresql" / "backup"
WATCHDOG = LINUX / "watchdog"

# 서버의 /etc/prometheus에 둘 규칙 파일과 저장소 원본.
RULE_SOURCES = {
    "school-alerts.yml": REPORTING / "school-alerts.yml",
    "backup-alerts.yml": BACKUP / "backup-alerts.yml",
    "watchdog-rules.yml": WATCHDOG / "watchdog-rules.yml",
    "checker-rules.yml": WATCHDOG / "checker-rules.yml",
}
UNITS = (OBSERVABILITY / "node_exporter.service", OBSERVABILITY / "node_exporter-host.service")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text(path):
    return path.read_text(encoding="utf-8")


def exec_args(unit):
    line = next(line for line in text(unit).splitlines() if line.startswith("ExecStart="))
    return line[len("ExecStart="):].split()


def unit_include(unit):
    value = next(arg for arg in exec_args(unit) if arg.startswith("--collector.systemd.unit-include="))
    # node_exporter는 이 값을 ^(?:...)$로 감싸 유닛 이름 전체와 맞춘다.
    return re.compile("(?:%s)" % value.split("=", 1)[1])


def env_value(path, key):
    for line in text(path).splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1]
    raise AssertionError(f"{path.name}에 {key}가 없다")


def rule_units(rules):
    """규칙 식이 이름으로 가리키는 systemd 유닛. name!~"(a|b)[.]service" 형태의 제외 목록도 푼다."""
    names = set(re.findall(r'name="([A-Za-z0-9@_.-]+[.](?:service|timer))"', rules))
    for group, suffix in re.findall(r'name!~"\(([A-Za-z0-9|_-]+)\)\[\.\](service|timer)"', rules):
        names.update(f"{name}.{suffix}" for name in group.split("|"))
    return names


class RuleFilesTest(unittest.TestCase):
    def test_rule_files_list_every_repository_rule_at_its_server_path(self):
        body = text(OBSERVABILITY / "prometheus.yml")
        block = body.split("\nrule_files:\n", 1)[1].split("\nalerting:", 1)[0]
        listed = [line.strip()[2:] for line in block.splitlines() if line.strip().startswith("- ")]
        self.assertEqual(listed, ["/etc/prometheus/" + name for name in RULE_SOURCES])
        for source in RULE_SOURCES.values():
            self.assertTrue(source.is_file(), source)

    def test_watchdog_rule_goes_only_to_the_null_receiver(self):
        self.assertIn("- alert: Watchdog\n", text(WATCHDOG / "watchdog-rules.yml"))
        lines = [line for line in text(REPORTING / "alertmanager.school.yml").splitlines()
                 if line.strip() and not line.strip().startswith("#")]
        routes = lines.index("  routes:")
        # 첫 하위 경로가 Watchdog이고 continue 없이 빈 수신자로 끝나야 기본 두 경로가 받지 않는다.
        self.assertEqual(lines[routes + 1:routes + 4], [
            '    - matchers: [alertname="Watchdog"]', "      receiver: watchdog-null", "    - receiver: teams"])
        receivers = lines.index("receivers:")
        self.assertEqual(lines[receivers + 1:receivers + 3], ["  - name: watchdog-null", "  - name: teams"])


class NodeExporterTest(unittest.TestCase):
    def test_both_units_collect_the_same_things_except_the_listen_address(self):
        host, bot = (
            [arg for arg in exec_args(unit) if not arg.startswith("--web.listen-address=")] for unit in UNITS)
        self.assertEqual(host, bot)
        self.assertIn("--collector.textfile", host)
        self.assertIn("--collector.textfile.directory=${NODE_EXPORTER_TEXTFILE_DIR}", host)
        for unit in UNITS:
            body = text(unit).splitlines()
            # 기본값을 비워 두어 textfile.env가 없는 호스트는 textfile을 읽지 않고 수집 오류도 내지 않는다.
            self.assertIn("Environment=NODE_EXPORTER_TEXTFILE_DIR=", body)
            self.assertIn("EnvironmentFile=-/etc/node_exporter/textfile.env", body)

    def test_unit_include_covers_units_that_rules_and_installers_name(self):
        rules = "".join(text(path) for path in RULE_SOURCES.values())
        expected = rule_units(rules) | {path.name for path in (
            LINUX / "bot" / "exchange-bot.service", LINUX / "bot" / "exchange-bot-capacity.service",
            BACKUP / "failstop-db-backup.service", WATCHDOG / "failstop-watchdog.service",
            REPORTING / "alertmanager.service", OBSERVABILITY / "prometheus.service",
            OBSERVABILITY / "postgres_exporter.service", OBSERVABILITY / "node_exporter.service")}
        self.assertTrue({"failstop-db-backup.service", "failstop-watchdog.service"} <= expected)
        for unit in UNITS:
            pattern = unit_include(unit)
            missing = sorted(name for name in expected if not pattern.fullmatch(name))
            self.assertEqual(missing, [], unit.name)

    def test_units_left_out_of_the_generic_failure_rule_have_their_own_rule(self):
        school = text(REPORTING / "school-alerts.yml")
        excluded = re.search(r'alert: LinuxServiceFailed\n\s+expr: [^\n]*name!~"\(([^)]+)\)\[\.\]service"', school)
        dedicated = {"failstop-reporting": ("OperationsReporterNotActive", school),
                     "exchange-bot": ("MarketBotNotActive", school),
                     "failstop-db-backup": ("DatabaseBackupServiceFailed", text(BACKUP / "backup-alerts.yml"))}
        self.assertEqual(sorted(excluded.group(1).split("|")), sorted(dedicated))
        for name, (alert, rules) in dedicated.items():
            rule = rules.split(f"- alert: {alert}\n", 1)[1].split("- alert:", 1)[0]
            self.assertIn(f'name="{name}.service"', rule)

    def test_textfile_directories_match_what_the_writers_use(self):
        backup_dir = env_value(OBSERVABILITY / "node_exporter-textfile.env.db", "NODE_EXPORTER_TEXTFILE_DIR")
        backup = load("db_backup", BACKUP / "db-backup.py")
        self.assertEqual(backup_dir, backup.CONFIG_DEFAULTS["TEXTFILE_DIR"])
        read_write = env_value(BACKUP / "failstop-db-backup.service", "ReadWritePaths").split()
        self.assertIn(backup_dir, read_write)
        self.assertEqual(env_value(BACKUP / "db-backup.conf.example", "TEXTFILE_DIR"), backup_dir)

        watchdog_dir = env_value(OBSERVABILITY / "node_exporter-textfile.env.watchdog", "NODE_EXPORTER_TEXTFILE_DIR")
        for profile in ("app-1", "bot-1"):
            config = json.loads(text(WATCHDOG / f"config.{profile}.json"))
            self.assertEqual(str(PurePosixPath(config["textfile"]).parent), watchdog_dir, profile)
        self.assertIn(watchdog_dir, text(WATCHDOG / "install-watchdog.sh"))


class MetricNameTest(unittest.TestCase):
    def test_backup_consumers_read_names_the_backup_tool_writes(self):
        backup = load("db_backup", BACKUP / "db-backup.py")
        history = {"count": 1, "last_success": 1_700_000_000, "last_bytes": 1024, "offsite_last_success": 1_700_000_000}
        emitted = set()
        for state in ({"eligible": True, "success": True, "verified": True, "offsite": "VERIFIED"},
                      {"eligible": True, "success": False, "verified": False, "offsite": "FAILED"},
                      {"eligible": False, "success": False, "verified": None, "offsite": None}):
            rendered = backup.render_metrics(state, history, True, 1_700_000_100)
            emitted |= {line.split()[0] for line in rendered.splitlines() if line and not line.startswith("#")}
        dashboard = json.loads(text(OBSERVABILITY / "msp-dashboards" / "15-msp-db-replication.json"))
        report = json.loads(text(REPORTING / "config.school.json"))
        consumers = {
            "backup-alerts.yml": text(BACKUP / "backup-alerts.yml"),
            "15-msp-db-replication.json": json.dumps(dashboard, ensure_ascii=False),
            "config.school.json": json.dumps(report["queries"], ensure_ascii=False),
        }
        for name, body in consumers.items():
            referenced = set(re.findall(r"failstop_(?:db_)?backup_[a-z_]+", body))
            self.assertTrue(referenced, name)
            self.assertEqual(sorted(referenced - emitted), [], name)

    def test_watchdog_checker_reads_names_the_watchdog_writes(self):
        watchdog = load("failstop_watchdog_wiring", WATCHDOG / "failstop_watchdog.py")
        results = [{"name": "prometheus", "target": "bot-1", "ok": True}]
        state = {"checks": {"prometheus": {"alerting": False}}, "outbox": []}
        rendered = watchdog.render_textfile(state, results, 1_700_000_000)
        emitted = {re.split(r"[{ ]", line, maxsplit=1)[0] for line in rendered.splitlines() if line and not line.startswith("#")}
        referenced = set(re.findall(r"failstop_watchdog_[a-z_]+", text(WATCHDOG / "checker-rules.yml")))
        self.assertTrue(referenced)
        self.assertEqual(sorted(referenced - emitted), [])

    def test_application_counters_in_rules_are_registered_by_the_server(self):
        # Micrometer 1.15(Spring Boot 3.5)의 Prometheus 노출은 점을 밑줄로 바꾸고 카운터에 _total을 붙인다.
        # 2026-09-23 서버와 같은 micrometer-registry-prometheus 1.15.12로 이 규칙을 실제 스크레이프해 확인했다.
        sources = "".join(text(path) for path in (REPO / "server").glob("*/src/main/java/**/*.java"))
        counters = {name.replace(".", "_") + "_total" for name in
                    re.findall(r'(?:FunctionCounter|Counter)\s*\.builder\(\s*"([a-z0-9_.]+)"', sources)}
        referenced = set(re.findall(r"\bexchange_[a-z_]+_total\b", text(REPORTING / "school-alerts.yml")))
        self.assertEqual(referenced, {"exchange_settlement_transient_exhausted_total",
                                      "exchange_order_intake_unresolved_total",
                                      "exchange_order_intake_commit_recovered_total"})
        self.assertEqual(sorted(referenced - counters), [])


if __name__ == "__main__":
    unittest.main()
