"use client";

/**
 * 인스턴스 상태 표. 구성도의 노드를 AWS 역할 이름으로 한 줄씩 세우고, 그 역할을 지금 맡은 로컬
 * Docker 컨테이너나 호스트 프로세스를 옆 열에 적는다. 이름은 관제센터와 같다. 순서는 요청 경로다.
 * CloudFront 와 ALB 로 들어와 매칭 노드 두 대(쓰기)와 Auto Scaling 그룹의 조회 노드 세 대(조회)를 지나
 * DB 두 대에 닿고, 그 뒤에 경로 밖에서 스택을 움직이거나 지켜보는 것(봇, 관측, 부하)을 둔다. 정렬을
 * 두지 않는 이유가 그것이다. 뒤섞으면 어디서 끊겼는지 읽기 어렵다.
 *
 * Auto Scaling 은 구성도에서 노드가 아니라 조회 노드 세 대를 감싸는 그룹 상자다. 표에서는 그 상자의 머리를
 * 조회 노드 세 줄 바로 위에 한 줄로 세우고, 세 줄은 이름을 한 단 들여 트리 선으로 그 머리에 매단다.
 * 그룹 줄의 값은 nodeViews 가 아니라 asgView 에서 온다.
 *
 * 구성도가 모양으로 보이는 것을 이 표는 글로 다시 적는다. 색과 선을 못 보는 사람도 같은 상태를
 * 읽어야 하고, 캡처 한 장에 포트와 비고까지 남아야 발표 자료로 쓸 수 있다.
 * 상태·역할은 flowSim 의 nodeViews 와 asgView 에서 오고 값은 시뮬레이션이다. 호스트 포트만
 * docker-compose.chaos.yml 의 실제 값이다.
 */

import DataTable, { type DataColumn } from "@/components/DataTable";
import {
  APP_NAME,
  READ_IDS,
  APP_PORT,
  LEASE_TTL,
  asgView,
  writable,
  type AppId,
  type AsgView,
  type FlowState,
  type NodeId,
  type NodeState,
  type NodeView,
} from "@/lib/admin/flowSim";
import { FlowIcon, type FlowIconName } from "./FlowIcon";
import styles from "./panels.module.css";

/** 표의 줄. 사용자 노드는 경로의 시작점이라 줄을 두지 않고, Auto Scaling 그룹은 노드가 아니라 따로 더한다 */
type RowId = Exclude<NodeId, "users"> | "asg";

interface Row {
  id: RowId;
  /** 호스트에 열린 포트. 여는 포트가 없거나 아직 없는 컨테이너는 없음 */
  port: string;
  /** 구성도 카드·그룹 머리와 같은 아이콘 */
  icon: FlowIconName;
}

/** 한 줄이 칸에 쓰는 값. 노드 줄은 nodeViews 에서, 그룹 줄은 asgView 에서 같은 모양으로 맞춘다 */
type RowView = Pick<NodeView, "name" | "container" | "role" | "status" | "state">;

const isApp = (id: RowId): id is AppId =>
  id === "api1" || id === "api2" || id === "read1" || id === "read2" || id === "read3";

/* 포트는 docker-compose.chaos.yml 의 ports 항목이다. db-standby 는 2단계에서 붙일 컨테이너라 아직 포트가 없다.
   read-1·2 는 전용 컨테이너가 없어 조회를 겸하는 매칭 노드 컨테이너의 포트를 적는다.
   Auto Scaling 줄은 그룹 머리라 조회 노드 세 줄 바로 위에 둔다. 구성도에서 조회 노드를 감싸는 상자와 같은 자리이고
   아이콘도 그 상자 머리와 같은 그룹 그림이다. 같은 대상이 두 화면에서 다른 그림이면 다른 것으로 읽힌다.
   ALB 도 구성도 카드와 같은 부하 분산 그림을 쓴다. 이 대역에서 ALB 를 대신하는 nginx(chaos-lb)는
   컨테이너 열과 팝오버가 이름으로 적으므로 아이콘까지 nginx 로 두면 AWS 역할 이름과 어긋난다 */
const ROWS: Row[] = [
  { id: "cdn", port: "없음", icon: "globe" },
  { id: "alb", port: ":58080", icon: "lb" },
  { id: "api1", port: `:${APP_PORT.api1}`, icon: "springboot" },
  { id: "api2", port: `:${APP_PORT.api2}`, icon: "springboot" },
  { id: "asg", port: "없음", icon: "asg" },
  { id: "read1", port: `:${APP_PORT.read1}`, icon: "springboot" },
  { id: "read2", port: `:${APP_PORT.read2}`, icon: "springboot" },
  { id: "read3", port: `:${APP_PORT.read3}`, icon: "springboot" },
  { id: "pg1", port: ":55432", icon: "postgresql" },
  { id: "pg2", port: "없음", icon: "postgresql" },
  { id: "bot", port: "없음", icon: "bot" },
  { id: "obs", port: "없음", icon: "prometheus" },
  { id: "load", port: "없음", icon: "load" },
];

/* Auto Scaling 줄의 컨테이너 칸. 그룹의 희망 대수를 따라 앱 컨테이너를 켜고 끄는 호스트 프로세스다
   (harness/asg/capacity_reconciler.py). 덧말은 nodeViews 의 load-1 과 같은 꼴로 적어 같은 표지로 그린다 */
const ASG_CONTAINER = "capacity_reconciler.py · 호스트";

/* 이름 칸의 트리 선. 그룹 머리 아이콘 아래에서 세로선이 내려와 조회 노드 세 줄에 가지를 치고 마지막 줄에서
   꺾여 끝난다. 구성도의 그룹 상자를 표에서 다시 읽게 하는 장치이고 선 모양은 panels.module.css 가 그린다 */
const TREE: Partial<Record<RowId, "group" | "member" | "last">> = {
  asg: "group",
  read1: "member",
  read2: "member",
  read3: "last",
};

/** 구성도와 같은 상태어. 형태가 색 없이도 상태를 가른다. 미구축은 형태 없이 점선 테두리와 낱말만 둔다 */
const STATE_SHAPE: Record<NodeState, string | null> = {
  running: "●",
  stopped: "○",
  failure: "■",
  transition: "▲",
  absent: null,
};
const STATE_WORD: Record<NodeState, string> = {
  running: "실행",
  stopped: "중지",
  failure: "장애",
  transition: "전환 중",
  absent: "미구축",
};

/* CPU 열에서 컨테이너가 아닌 줄. 컨테이너 CPU 는 chaos-cstats 가 재므로 호스트 프로세스와 이 대역에
   없는 구성 요소(CloudFront)에는 잴 대상 자체가 없다 */
const NO_CONTAINER: ReadonlySet<RowId> = new Set<RowId>(["cdn", "load", "asg"]);

export interface InstanceTableProps {
  state: FlowState;
  views: Record<NodeId, NodeView>;
  cpuNow: Record<AppId, number | null>;
}

export default function InstanceTable({ state, views, cpuNow }: InstanceTableProps) {
  const leader = state.holder;
  const asg = asgView(state);
  const viewOf = (id: RowId): RowView => (id === "asg" ? groupView(asg) : views[id]);

  const columns: DataColumn<Row>[] = [
    {
      key: "name",
      label: "이름",
      // 하한은 그룹에 든 리더 줄이 잘리지 않는 폭이다. 트리 들임(24) + 아이콘 + app-1 + 리더 표지가 들어간다
      width: "minmax(148px, 0.9fr)",
      align: "left",
      render: (r) => {
        const isLeader = r.id === leader;
        return (
          <span className={styles.instNameCell} data-tree={TREE[r.id]}>
            <span
              className={styles.instName}
              data-leader={isLeader ? "true" : undefined}
              data-group={r.id === "asg" ? "true" : undefined}
            >
              <span className={styles.instGlyph}>
                <FlowIcon name={r.icon} size={16} />
              </span>
              <span className={styles.instNameText}>{viewOf(r.id).name}</span>
              {isLeader && <span className={styles.leaderTag}>리더</span>}
            </span>
          </span>
        );
      },
    },
    {
      key: "container",
      label: "컨테이너",
      width: "minmax(0, 1.15fr)",
      align: "left",
      render: (r) => <ContainerCell value={viewOf(r.id).container} />,
    },
    {
      key: "role",
      label: "역할",
      width: "minmax(0, 1fr)",
      align: "left",
      render: (r) => {
        const role = viewOf(r.id).role;
        return <span title={role}>{role}</span>;
      },
    },
    {
      key: "status",
      label: "상태",
      width: "minmax(0, 1.25fr)",
      align: "left",
      render: (r) => {
        const v = viewOf(r.id);
        const detail = statusDetail(state, r.id, v);
        return (
          <span className={styles.statusCell} title={detail ?? undefined}>
            <StateChip state={v.state} />
            {detail !== null && <span className={styles.statusDetail}>{detail}</span>}
          </span>
        );
      },
    },
    {
      key: "port",
      label: "포트",
      // 수치 두 열은 가장 긴 값(:58080, 재생 제외)에 맞춘 폭이다. 남는 폭은 이름 칸의 트리 들임에 준다
      width: "56px",
      align: "right",
      render: (r) => <span className={r.port === "없음" ? styles.instDim : "num"}>{r.port}</span>,
    },
    {
      key: "cpu",
      label: "CPU",
      width: "56px",
      align: "right",
      render: (r) => {
        if (NO_CONTAINER.has(r.id)) {
          return (
            <span className={styles.instDim} title="컨테이너 아님 · chaos-cstats 수집 대상 밖">
              없음
            </span>
          );
        }
        // 앱 노드가 아닌 컨테이너도 chaos-cstats 가 실제로 잰다. 이 시안이 재생하지 않을 뿐이라
        // 해당 없음이 아니라 재생 제외로 적는다
        if (!isApp(r.id)) {
          return (
            <span className={styles.instDim} title="chaos-cstats 수집 · 시안은 앱 노드만 재생">
              재생 제외
            </span>
          );
        }
        const v = cpuNow[r.id];
        // 꺼진 컨테이너는 chaos-cstats 가 잴 대상이 없어 값이 없다. 0% 로 적으면 한가한 노드로 읽힌다
        if (v === null)
          return (
            <span className={styles.instDim}>
              <span aria-hidden="true">◌ </span>
              미수집
            </span>
          );
        return <span className="num">{Math.round(v)}%</span>;
      },
    },
    {
      key: "note",
      label: "비고",
      width: "minmax(0, 1.4fr)",
      align: "left",
      render: (r) => {
        const note = noteText(state, r.id, asg);
        return <span title={note}>{note}</span>;
      },
    },
  ];

  // 글 열은 왼쪽, 수치 열(포트, CPU)은 오른쪽에 붙인다. 가운데 정렬이면 이름 길이마다 아이콘과 첫 글자가
  // 다른 자리에 서서 한 열로 읽히지 않고, 자릿수가 다른 숫자끼리 끝이 맞지 않는다
  return (
    <div className={styles.instTable}>
      <DataTable
        columns={columns}
        rows={ROWS}
        rowKey={(r) => r.id}
        rowHeight={44}
        ariaLabel="인스턴스 상태"
      />
    </div>
  );
}

/**
 * 상태 칩. 구성도 카드의 상태 테두리를 칩 크기로 옮긴다. 실행은 초록 실선, 전환 중은 주황 점선,
 * 장애는 빨강 실선과 옅은 면, 중지는 회색 실선, 미구축은 회색 점선이다. 앞의 형태가 색 없이도
 * 상태를 가르고, 미구축은 형태 대신 낱말과 점선이 가른다
 */
function StateChip({ state }: { state: NodeState }) {
  const shape = STATE_SHAPE[state];
  return (
    <span className={styles.stateChip} data-state={state}>
      {shape !== null && (
        <span className={styles.stateShape} aria-hidden="true">
          {shape}
        </span>
      )}
      <span className={styles.stateLabel}>{STATE_WORD[state]}</span>
    </span>
  );
}

/**
 * 컨테이너 열. nodeViews 는 컨테이너 이름과 덧말을 가운뎃점으로 이어 준다("read-load.py · 호스트").
 * 식별자는 고정폭으로 한 줄에 하나씩 쌓고 한글 덧말(호스트, 2단계 추가)은 테두리 친 작은 표지로
 * 식별자 뒤에 붙인다. 열이 좁아 한 줄에 다 들지 않으면 표지만 둘째 줄로 내려간다. 식별자를 줄여
 * 자르지 않기 위해서다. 덧말을 맨 글자로 둘째 줄에 두면 11px 한글의 가로획(ㅗ, ㅡ)이 한 줄로 이어져
 * 밑줄 친 링크처럼 읽혔다
 */
function ContainerCell({ value }: { value: string | null }) {
  if (value === null) return <span className={styles.instDim}>없음</span>;
  const parts = value.split(" · ");
  const ids = parts.filter((p) => /^[\x21-\x7e]+$/.test(p));
  const quals = parts.filter((p) => !/^[\x21-\x7e]+$/.test(p));
  return (
    <span className={styles.instContainer} title={value}>
      <span className={styles.instIds}>
        {ids.map((p) => (
          <span key={p} className={styles.instMono}>
            {p}
          </span>
        ))}
      </span>
      {quals.map((p) => (
        <span key={p} className={styles.instQual}>
          {p}
        </span>
      ))}
    </span>
  );
}

/**
 * Auto Scaling 그룹 줄의 값. 그룹은 켜지고 꺼지는 노드가 아니라 목표 대수를 맞추는 묶음이라 상태는
 * 실행과 전환 중 둘뿐이다. 역할 칸에는 그룹의 하한과 상한을 적는다
 */
function groupView(a: AsgView): RowView {
  return {
    name: "Auto Scaling",
    container: ASG_CONTAINER,
    role: `min ${a.min} · max ${a.max}`,
    status: a.status,
    state: a.state,
  };
}

/**
 * 상태 칩 옆의 덧말. nodeViews 의 상태 한 줄에서 칩이 이미 말한 낱말을 뺀다. 정상으로 도는 앱은
 * 상태 자리에 CPU 를 적는데 표에는 CPU 열이 따로 있어 덧말을 두지 않는다
 */
function statusDetail(s: FlowState, id: RowId, view: RowView): string | null {
  if (isApp(id) && s.apps[id].status === "running" && view.state === "running") return null;
  const word = STATE_WORD[view.state];
  const rest = view.status.split(" · ").filter((seg) => seg !== word && seg !== `${word} 중`);
  return rest.length > 0 ? rest.join(" · ") : null;
}

/** 비고 열. 지금 상태에서 운영자가 다음에 볼 것, 또는 이 대역의 한계를 한 줄로 적는다 */
function noteText(s: FlowState, id: RowId, asg: AsgView): string {
  switch (id) {
    case "cdn":
      // 구성도는 목표 구성대로 켜진 노드로 그린다. 표는 이 대역에서 요청이 ALB 로 바로 들어간다는 사실을 남긴다
      return "Docker 대역에 없음 · 목표 구성";
    case "alb": {
      // 쓰기 업스트림은 nginx 설정에 고정돼 있다. 리더가 바뀌어도 app-1 을 먼저 두드리고 실패하면 app-2 로 넘긴다
      const readers = READ_IDS.filter((a) => s.apps[a].inRead).length;
      return `쓰기 ${APP_NAME.api1} 우선 · 조회 ${readers}대`;
    }
    case "api1":
    case "api2":
      return engineNote(s, id);
    case "read1":
    case "read2":
      // AWS 에서는 조회 전용 노드다. Docker 대역에는 전용 컨테이너가 없어 매칭 노드 컨테이너가 조회를 겸한다
      return `Docker 대역 · ${APP_NAME[id === "read1" ? "api1" : "api2"]} 컨테이너가 조회 겸함`;
    case "read3":
      switch (s.apps.read3.status) {
        case "stopped":
          return "운영자 승인 뒤 조정기가 기동";
        case "booting":
          return "ALB 등록 대기";
        case "draining":
          return "ALB 제외 · 드레이닝 10초";
        default:
          // Prometheus 가 앱 지표를 긁는 대상은 app-1, app-2, 봇뿐이라 증설 노드의 앱 지표는 비어 있다.
          // 컨테이너 CPU 는 chaos-cstats 가 실행 중인 컨테이너 전부에서 내므로 read-3 도 받는다
          return "앱 지표 미수집 · CPU 는 cstats";
      }
    case "pg1":
      // DB 지표 수집기는 chaos-pg 한 대만 본다. db-standby 가 붙어도 지금 설정으로는 복제 상대의 지표가 없다
      return "DB 지표 chaos-pg-exporter";
    case "pg2":
      return "2단계 추가 · 비동기 복제 예정";
    case "bot":
      return "ALB 로 주문 · 봇 계정 DB 직결";
    case "obs":
      // 컨테이너 CPU 는 chaos-cstats 가 실행 중인 컨테이너 전부에서 내므로 빠지는 쪽(앱 지표)만 적는다
      return "앱 지표 app-1·app-2·봇만";
    case "load":
      return "호스트에서 ALB :58080 조회";
    case "asg":
      // 목표는 운영자가 승인한 희망 대수, 등록은 ALB 조회 대상에 든 앱 수다. 둘이 다르면 조정 중이다
      return `목표 ${asg.desired} · 등록 ${asg.registered}`;
  }
}

/** 매칭 리더를 다투는 두 노드의 비고. 리스는 DB 의 leader_lease 에 있다 */
function engineNote(s: FlowState, id: "api1" | "api2"): string {
  const node = s.apps[id];
  if (s.holder === id) {
    if (writable(s)) return `리스 보유 · epoch ${s.epoch}`;
    // 보유자가 죽거나 DB 에 못 닿아도 리스가 만료되기 전까지는 누구도 넘겨받지 못한다.
    // 만료되면 flowSim 이 보유자를 비우므로 이 줄에 오는 동안 잔여는 늘 1초 이상이다
    return `리스 갱신 중단 · 잔여 ${s.lease}초`;
  }
  switch (node.status) {
    case "killed":
      return "재기동 전까지 중단";
    case "booting":
      return "기동 뒤 리더 대기 합류";
    default:
      // 보유자가 빈 구간은 리스가 이미 만료되고 아직 아무도 잡지 않은 승격 공백이다
      return s.holder === null ? "리스 만료 · 승격 대기" : `리스 만료 시 승격 · TTL ${LEASE_TTL}초`;
  }
}
