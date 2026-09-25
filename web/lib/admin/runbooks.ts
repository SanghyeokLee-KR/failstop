/**
 * 런북(관리자 설계 4-10-4). 사람이 운영 노드에서 하는 절차다. AI가 할 수 없는 일(ADR 0035 금지 목록)과 허용 목록 밖의
 * 일을 콘솔은 실행하지 않고, 운영자가 SSM 세션에서 이 순서대로 확인하고 되돌린다. 두 데이터 원천이 같은 정적 자료다.
 *
 * 명령은 원문 문서에 적힌 것만 옮기고 새 명령을 짓지 않는다. 원문에 명령이 없는 단계는 할 일과 확인 조건만 적는다.
 * 원문은 sources의 경로와 절이다. 원문이 바뀌면 이 표를 같은 변경에서 고친다.
 */

import type { OpsAlert, Runbook, RunbookId } from "./types";

const AWS = "docs/reports/AWS_작업.md";
const FAILOVER = "docs/reference/operations/DB_수동_페일오버.md";
const BACKUP = "deploy/linux/postgresql/backup/README.md";

export const RUNBOOKS: Runbook[] = [
  {
    id: "DB_PROMOTE",
    title: "DB 수동 승격",
    when: "Primary가 응답하지 않아 Standby를 새 Primary로 올려야 할 때",
    notForAi: "DB 승격·페일오버는 AI 금지 목록 1입니다. 자동 모드에서도 AI는 승격을 제안하거나 실행하지 않습니다",
    alerts: [
      { sim: "DB 연결 실패", rule: "DatabaseConnectionFailed" },
      { sim: "복제 확인 불가", rule: "DatabaseReplicationUnknown" },
    ],
    steps: [
      {
        title: "장애 확인",
        detail: "Primary가 실제로 쓰지 못하는 상태인지 보고, 네트워크만 끊긴 것인지 프로세스가 죽은 것인지 가릅니다. 앱의 /actuator/health와 리스 갱신 오류도 함께 봅니다.",
        check: "Primary가 응답하지 않는 원인을 가렸습니다",
        commands: ["pg_isready -h <primary> -p 5432 -U <db_user> -d exchange"],
      },
      {
        title: "옛 Primary 격리",
        detail: "봇, 앱 순서로 멈추고 네 테이블 건수와 WAL 위치를 기록합니다. 옛 Primary의 PostgreSQL을 멈추고 mask합니다. 이 단계를 건너뛰지 않습니다.",
        check: "프로세스, 5432 listen, 앱에서의 접속 거부까지 확인하기 전에는 승격하지 않습니다",
        commands: ["systemctl stop postgresql", "systemctl mask postgresql"],
      },
      {
        title: "복제 위치 확인",
        detail: "승격 전에 receive·replay WAL 위치와 마지막 재생 시각을 기록합니다. 두 위치가 벌어져 있으면 재생이 끝나기를 기다립니다.",
        check: "Standby가 기준 WAL 위치까지 재생했습니다. 유실 범위는 WAL 차이가 아니라 주문 ACK·저널·체결로 판정합니다",
        commands: ["SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(), now() - pg_last_xact_replay_timestamp() AS replay_delay;"],
      },
      {
        title: "Standby 승격",
        detail: "Standby를 승격하고 복구 모드를 벗어났는지, 기준 데이터가 남았는지 확인합니다. 승격은 되돌릴 수 없습니다.",
        check: "pg_is_in_recovery()가 false이고 네 테이블 건수가 기준과 같습니다",
        commands: ["SELECT pg_promote(true,60)", "SELECT pg_is_in_recovery();"],
      },
      {
        title: "앱·봇 주소 전환",
        detail: "기존 설정과 인증서를 백업하고 앱·봇의 DB 주소를 새 Primary로 바꿉니다. 앱부터 시작해 health UP을 확인한 뒤 봇을 시작합니다.",
        check: "거래 건수가 실제로 늘고 화면이 연결됩니다",
        commands: ["switch-db-endpoint.py <app|bot> apply --sha256 <검증한 지문>"],
      },
      {
        title: "리더 리스 재획득",
        detail: "앱이 새 Primary에 붙으면 리더 리스를 다시 잡습니다. 이 부분은 자동이고 운영자는 결과만 확인합니다.",
        check: "epoch가 단조 증가했고 exchange_leader_is_leader가 한 노드에서만 1입니다",
        commands: ["SELECT resource, holder, epoch, lease_until FROM leader_lease;"],
      },
      {
        title: "옛 Primary 재편입",
        detail: "타임라인이 갈렸으므로 옛 Primary에 새 베이스백업을 받아 Standby로 붙입니다. 옛 PGDATA는 보존하고 지우지 않습니다.",
        check: "양쪽에서 streaming과 WAL 송신·재생 위치가 맞습니다",
        commands: ["prepare-db-recovery.py rejoin-old --server-sha256 <검증한 지문>"],
      },
    ],
    rollback: "승격은 되돌릴 수 없습니다. 타임라인이 갈려 옛 Primary는 그대로 붙지 않고 새 베이스백업으로 Standby가 됩니다(7단계). 옛 PGDATA를 지우거나 강제로 다시 Primary로 시작하지 않습니다.",
    sources: [
      { path: FAILOVER, section: "2. 절차" },
      { path: AWS, section: "DB 수동 전환을 재현할 때" },
    ],
  },
  {
    id: "APP_RESTART",
    title: "앱 재시작",
    when: "앱이 응답하지 않거나 서비스가 실패 상태일 때",
    notForAi: "앱·DB 재시작은 AI 금지 목록 5입니다. 원인을 남기지 않고 재시작하면 같은 장애가 되풀이됩니다",
    alerts: [
      { sim: null, rule: "SpringMetricsUnavailable" },
      { sim: null, rule: "LinuxServiceFailed(exchange-app)" },
    ],
    steps: [
      {
        title: "죽었는지 멈췄는지 가르기",
        detail: "공개 API의 응답 코드와 시간을 봅니다. 502면 프로세스가 죽었고, 504면 연결은 되지만 응답이 오지 않습니다.",
        check: "502와 504 가운데 어느 쪽인지 가렸습니다",
        commands: ["curl -s -o /dev/null -w '%{http_code} %{time_total}s\\n' --max-time 40 https://www.sang.it.kr/api/v1/markets"],
      },
      {
        title: "자원과 DB 배제",
        detail: "메모리, 디스크, CPU steal과 DB 5432 연결을 먼저 확인합니다. steal이 0이면 CPU 크레딧 문제가 아닙니다.",
        check: "자원과 DB가 원인이 아니라는 것을 확인했습니다",
        commands: ["free -m; df -h /; vmstat 1 3", "timeout 5 bash -c '</dev/tcp/172.31.33.195/5432' && echo \"DB OK\" || echo \"DB FAIL\""],
      },
      {
        title: "재시작 전 스레드 덤프",
        detail: "재시작하면 원인이 사라지므로 덤프를 먼저 받습니다. SIGQUIT은 프로세스를 죽이지 않고, 덤프는 journald에서 읽습니다.",
        check: "덤프 파일이 남았고 멈춘 지점을 확인했습니다",
        commands: [
          "sudo kill -3 $(systemctl show -p MainPID --value exchange-app); sleep 3",
          "sudo journalctl -u exchange-app --since '-1min' --no-pager > /tmp/threaddump.txt",
        ],
      },
      {
        title: "재시작과 확인",
        detail: "원인을 기록한 뒤 운영자가 서비스를 재시작하고 health와 공개 API 응답, 봇 체결이 이어지는지 확인합니다.",
        check: "공개 API가 200을 반환하고 체결이 이어집니다",
        commands: [],
      },
    ],
    rollback: "재시작 자체는 되돌릴 것이 없습니다. 설정을 바꿔 재시작했다면 바꾸기 전 파일을 복원하고 다시 재시작합니다.",
    sources: [{ path: AWS, section: "2026-09-21 앱 무응답과 가상 스레드 철회 · 재현할 때의 진단 순서" }],
  },
  {
    id: "DISK_CLEANUP",
    title: "디스크 정리",
    when: "디스크나 inode 여유가 임계 아래로 내려갈 때",
    notForAi: "데이터·로그·백업 삭제는 AI 금지 목록 3입니다. 무엇을 지울지는 보존 요구를 아는 사람이 정합니다",
    alerts: [
      { sim: "디스크 여유 부족", rule: "LinuxDiskSpaceLow" },
      { sim: null, rule: "LinuxInodesLow" },
    ],
    steps: [
      {
        title: "커진 경로 찾기",
        detail: "어느 경로가 커졌는지 조회 명령으로 확인합니다. du는 I/O를 쓰므로 필요한 경로부터 봅니다.",
        check: "용량을 차지한 경로와 원인을 찾았습니다",
        commands: [
          "df -hT /",
          "df -i /",
          "sudo du -xsh /var/log /var/lib/pgsql/data",
          "sudo du -xsh /var/lib/pgsql/data/pg_wal",
          "sudo journalctl --disk-usage",
        ],
      },
      {
        title: "DB와 복제 슬롯 확인",
        detail: "DB 자체가 커졌는지, 복제 슬롯이 WAL을 붙잡는지 봅니다. pg_wal이나 데이터 파일을 rm으로 지우지 않습니다.",
        check: "DB 크기와 슬롯 상태를 기록했습니다",
        commands: [
          "sudo -u postgres psql -X -P pager=off -d exchange -c 'SELECT pg_size_pretty(pg_database_size(current_database()));'",
          "sudo -u postgres psql -X -P pager=off -d exchange -c 'SELECT slot_name, active, restart_lsn, wal_status FROM pg_replication_slots;'",
        ],
      },
      {
        title: "정리 판단",
        detail: "로그·패키지 캐시·오래된 배포 파일·백업이 원인이면 필요한 증거와 복구 파일을 보존한 뒤 그 프로그램의 정리 방법과 보존 기간을 정합니다. 새 백업의 복원을 확인하기 전에 마지막 복구본을 지우지 않습니다.",
        check: "지울 대상과 보존할 대상을 정하고 기록했습니다",
        commands: [],
      },
      {
        title: "봇 재개",
        detail: "용량 보호가 봇을 멈췄다면 여유가 돌아온 것을 확인한 뒤 운영자가 봇을 다시 켭니다. 용량 보호는 스스로 다시 켜지 않습니다.",
        check: "네 노드의 용량·inode 여유가 기준 위이고 봇이 running입니다",
        commands: ["sudo systemctl start exchange-bot"],
      },
    ],
    rollback: "지운 파일은 되돌릴 수 없습니다. 그래서 지우기 전에 증거와 복구 파일을 보존합니다. VACUUM FULL은 디스크가 찬 상황의 첫 조치로 실행하지 않습니다.",
    sources: [
      { path: AWS, section: "디스크가 차기 전에 확인하는 방법" },
      { path: AWS, section: "저장 공간이 부족해지기 전에 멈추기" },
    ],
  },
  {
    id: "CERT_RENEW",
    title: "인증서 갱신",
    when: "공개 인증서의 만료가 가깝거나 자동 갱신이 실패했을 때",
    notForAi: "인증서 변경은 AI 금지 목록 4입니다",
    alerts: [
      { sim: "인증서 만료 14일 이내", rule: null },
      { sim: null, rule: "LinuxServiceFailed(certbot-renew · 학교 미적용)" },
    ],
    steps: [
      {
        title: "갱신 타이머 확인",
        detail: "certbot-renew.timer가 켜져 있고 다음 실행이 예약됐는지 봅니다. 갱신이 실패하면 certbot-renew.service가 failed 상태가 됩니다.",
        check: "타이머가 활성이고 다음 실행 시각이 있습니다",
        commands: ["systemctl list-timers certbot-renew.timer --no-pager"],
      },
      {
        title: "모의 갱신",
        detail: "실제 갱신 전에 모의 갱신으로 HTTP-01 검증 경로와 갱신 훅이 도는지 확인합니다. 80 포트의 /.well-known/acme-challenge/가 열려 있어야 합니다.",
        check: "모의 갱신 결과가 success입니다",
        commands: ["sudo certbot renew --dry-run --run-deploy-hooks"],
      },
      {
        title: "갱신 기록 점검",
        detail: "갱신 서비스의 기록을 읽고, 훅이 nginx -t에 성공한 뒤 reload했는지 봅니다.",
        check: "갱신 서비스 기록에 오류가 없고 새 만료일이 보입니다",
        commands: ["journalctl -u certbot-renew.service"],
      },
    ],
    rollback: "문제가 생기면 .before-https 백업 두 파일을 확인한 뒤 /etc/nginx/nginx.conf와 /etc/exchange/app.env를 복원하고 앱 재시작, nginx -t, Nginx reload 순서로 되돌립니다.",
    sources: [{ path: AWS, section: "도메인과 인증서" }],
  },
  {
    id: "BOT_STOP",
    title: "봇 중지",
    when: "봇 부하를 줄여도 bot-1 CPU나 용량이 기준을 넘을 때",
    notForAi: "AI는 허용 목록의 봇 부하 줄임으로 한 번에 절반까지만 줄입니다. 봇 서비스 중지는 허용 목록 밖입니다",
    alerts: [
      { sim: "봇 용량 보호", rule: null },
      { sim: null, rule: "MarketBotNotActive" },
    ],
    steps: [
      {
        title: "봇 상태 확인",
        detail: "봇 서비스의 상태, 메모리, 재시작 횟수와 최근 로그를 봅니다.",
        check: "봇 상태와 메모리를 기록했습니다",
        commands: [
          "sudo systemctl show exchange-bot -p ActiveState -p MemoryCurrent -p NRestarts",
          "sudo journalctl -u exchange-bot -n 40 --no-pager -o cat",
        ],
      },
      {
        title: "봇 중지",
        detail: "봇 서비스를 멈추고 종료 로그를 확인합니다. 기존 주문과 거래 기록은 보존됩니다.",
        check: "종료 로그를 확인했고, 중지 직전 미체결 주문의 상태를 따로 확인했습니다",
        commands: ["sudo systemctl stop exchange-bot"],
      },
      {
        title: "재부팅 뒤 중지 유지",
        detail: "재부팅 뒤에도 멈춰 두어야 하면 자동 시작을 끕니다.",
        check: "필요할 때만 disable했고 그 사실을 기록했습니다",
        commands: ["sudo systemctl disable exchange-bot"],
      },
    ],
    rollback: "다시 켤 때는 sudo systemctl start exchange-bot으로 시작하고, disable했다면 enable도 함께 합니다. 메모리 압박이 다시 나면 속도를 올리지 않습니다.",
    sources: [{ path: AWS, section: "시장 봇을 시작하고 확인하는 방법" }],
  },
  {
    id: "BACKUP_RESTORE",
    title: "백업 복원",
    when: "백업이 실패하거나 늦어졌고, 복원 가능성을 확인해야 할 때",
    notForAi: "복원은 데이터를 바꾸는 일이라 허용 목록 밖입니다. AI가 할 수 있는 일은 백업 즉시 실행 한 가지입니다",
    alerts: [
      { sim: "백업 지연", rule: "DatabaseBackupStale" },
      { sim: null, rule: "DatabaseBackupRunFailed" },
      { sim: null, rule: "DatabaseBackupVerifyFailed" },
    ],
    steps: [
      {
        title: "실패 단계 확인",
        detail: "백업 서비스 기록의 JSON에서 stage와 error를 보고 원인 종류(설정, DB 접속, 디스크, 검증, 외부 보관)를 가릅니다.",
        check: "실패한 단계와 원인을 적었습니다",
        commands: ["sudo journalctl -u failstop-db-backup.service -n 60 --no-pager"],
      },
      {
        title: "백업 파일 대조",
        detail: "백업 디렉터리에서 SHA256SUMS를 대조하고 백업 지표 파일을 읽습니다.",
        check: "체크섬 대조가 통과했습니다",
        commands: [
          "sudo -u postgres sh -c 'cd /var/lib/pgsql/backups/basebackup/<ID> && sha256sum -c SHA256SUMS'",
          "cat /var/lib/node_exporter/textfile/failstop_db_backup.prom",
        ],
      },
      {
        title: "격리 복원 검사",
        detail: "백업을 TCP 없는 시험 클러스터에 복원해 확인합니다. 운영 DB에는 접속하지 않고 운영 데이터 디렉터리를 복원 대상으로 삼지 않습니다.",
        check: "status가 PASS이고 counts_within_backup_window가 true이며 필수 정합성 실패가 0건입니다",
        commands: [
          "sudo -u postgres python3 -B /usr/local/lib/failstop/backup-restore-check.py --invariants /usr/local/lib/failstop/summary.sql --basebackup /var/lib/pgsql/backups/basebackup/<ID>",
        ],
      },
      {
        title: "실제 복구 판단",
        detail: "실제 장애 복구에 물리 백업을 쓰는 경로는 학교에서 실행한 적이 없습니다. 수동 승격과 앱 전환 순서는 DB 수동 승격 런북을 따릅니다.",
        check: "복구 경로와 유실 범위를 운영자가 정했습니다",
        commands: [],
      },
    ],
    rollback: "격리 복원 검사는 운영 DB를 바꾸지 않습니다. 실패하면 시험 클러스터를 원인 확인용으로 남깁니다.",
    sources: [
      { path: AWS, section: "2026-09-20 백업·복원과 보고서 연결" },
      { path: BACKUP, section: "복원 · 실패했을 때" },
    ],
  },
];

const BY_ID = new Map(RUNBOOKS.map((r) => [r.id, r]));

export function runbookById(id: RunbookId): Runbook {
  const r = BY_ID.get(id);
  if (!r) throw new Error(`런북 없음: ${id}`);
  return r;
}

/** 런북 화면 주소. 경보 행과 사건 드로어의 '런북' 링크가 이 주소로 그 런북을 고른다 */
export function runbookHref(id: RunbookId): string {
  return `/admin/runbooks?id=${id}`;
}

/**
 * 경보에 맞는 런북. 경보 이름(sim 경보 이름이나 서버 경보 규칙 이름)으로 찾고, 서비스 실패 규칙은 대상 서비스로 가른다.
 * 맞는 런북이 없으면 null이다. 복제 지연처럼 사람이 볼 뿐 따로 절차가 없는 경보는 짓지 않는다
 */
export function runbookForAlert(a: Pick<OpsAlert, "name" | "target" | "detail" | "thresholdKey">): RunbookId | null {
  const name = a.name.trim();
  if (name === "LinuxServiceFailed") {
    const text = `${a.target} ${a.detail}`;
    if (/exchange-app/.test(text)) return "APP_RESTART";
    if (/certbot/.test(text)) return "CERT_RENEW";
    return null;
  }
  for (const r of RUNBOOKS) {
    if (r.alerts.some((x) => x.sim === name || (x.rule !== null && x.rule === name))) return r.id;
  }
  if (a.thresholdKey === "host.disk_free_alert") return "DISK_CLEANUP";
  if (a.thresholdKey === "bot.capacity_guard") return "BOT_STOP";
  if (a.thresholdKey === "backup.delay_alert") return "BACKUP_RESTORE";
  return null;
}
