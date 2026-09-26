"use client";

/**
 * 인스턴스 상태 표. 구성도의 노드를 AWS 역할 이름으로 한 줄씩 세우고, 그 역할을 지금 맡은 로컬
 * Docker 컨테이너나 호스트 프로세스를 옆 열에 적는다. 이름은 관제센터와 같다. 순서는 요청 경로다.
 * CloudFront 와 ALB 로 들어와 매칭 리더 두 대(쓰기와 조회)와 Auto Scaling 그룹의 app-3(조회 전용)을 지나
 * DB 두 대에 닿고, 그 뒤에 경로 밖에서 스택을 움직이거나 지켜보는 것(봇, 관측, 부하)을 둔다. 정렬을
 * 두지 않는 이유가 그것이다. 뒤섞으면 어디서 끊겼는지 읽기 어렵다.
 *
 * Auto Scaling 은 구성도에서 노드가 아니라 app-3 자리를 감싸는 그룹 상자다. 표에서는 그 상자의 머리를
 * app-3 줄 바로 위에 한 줄로 세우고, app-3 은 이름을 한 단 들여 트리 선으로 그 머리에 매단다.
 * 그룹 줄의 값은 nodeViews 가 아니라 asgView 에서 온다.
 *
 * 구성도가 모양으로 보이는 것을 이 표는 글로 다시 적는다. 색과 선을 못 보는 사람도 같은 상태를
 * 읽어야 하고, 캡처 한 장에 포트와 비고까지 남아야 발표 자료로 쓸 수 있다.
 * 상태·역할은 flowSim 의 nodeViews 와 asgView 에서 오고 값은 시뮬레이션이다. 호스트 포트만
 * docker-compose.chaos.yml 의 실제 값이다.
 *
 * 관제 값 모드(state.live)에서는 CPU 와 비고가 관제 판의 값이다. DB·봇 호스트 CPU 는 서버·노드 화면과 같은 값이고,
 * 비고는 Docker 대역의 사실(앱 지표 미수집, 재생 제외) 대신 관제 판에서 읽히는 다음 볼 것을 적는다. 같은 줄의 CPU 가
 * 관제 판의 값인데 비고가 Docker 대역을 말하면 한 줄이 두 세계를 말한다. 컨테이너와 포트 열은 역할을 맡을
 * 로컬 컨테이너의 대응표라 두 모드에서 같다.
 */

import DataTable, { type DataColumn } from "@/components/DataTable";
import {
  APP_IDS,
  APP_NAME,
  APP_PORT,
  LEASE_TTL,
  asgView,
  isLeader,
  partitionsOf,
  writable,
  type AppId,
  type AsgView,
  type FlowState,
  type NodeId,
  type NodeState,
  type NodeView,
} from "@/lib/admin/flowSim";
import StatusShape, { type StatusShapeKind } from "../StatusShape";
import { StateChip, type ChipTone } from "../ui";
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

const isApp = (id: RowId): id is AppId => id === "api1" || id === "api2" || id === "app3";

/* 포트는 docker-compose.chaos.yml 의 ports 항목이다. db-standby 는 2단계에서 붙일 컨테이너라 아직 포트가 없다.
   Auto Scaling 줄은 그룹 머리라 app-3 줄 바로 위에 둔다. 구성도에서 app-3 을 감싸는 상자와 같은 자리이고
   아이콘도 그 상자 머리와 같은 그룹 그림이다. 같은 대상이 두 화면에서 다른 그림이면 다른 것으로 읽힌다.
   ALB 도 구성도 카드와 같은 부하 분산 그림을 쓴다. 이 대역에서 ALB 를 대신하는 nginx(chaos-lb)는
   컨테이너 열과 팝오버가 이름으로 적으므로 아이콘까지 nginx 로 두면 AWS 역할 이름과 어긋난다 */
const ROWS: Row[] = [
  { id: "cdn", port: "없음", icon: "globe" },
  { id: "alb", port: ":58080", icon: "lb" },
  { id: "api1", port: `:${APP_PORT.api1}`, icon: "springboot" },
  { id: "api2", port: `:${APP_PORT.api2}`, icon: "springboot" },
  { id: "asg", port: "없음", icon: "asg" },
  { id: "app3", port: `:${APP_PORT.app3}`, icon: "springboot" },
  { id: "pg1", port: ":55432", icon: "postgresql" },
  { id: "pg2", port: "없음", icon: "postgresql" },
  { id: "bot", port: "없음", icon: "bot" },
  { id: "obs", port: "없음", icon: "prometheus" },
  { id: "load", port: "없음", icon: "load" },
];

/* Auto Scaling 줄의 컨테이너 칸. 그룹의 희망 대수를 따라 앱 컨테이너를 켜고 끄는 호스트 프로세스다
   (harness/asg/capacity_reconciler.py). 덧말은 nodeViews 의 load-1 과 같은 꼴로 적어 같은 표지로 그린다 */
const ASG_CONTAINER = "capacity_reconciler.py · 호스트";

/* 이름 칸의 트리 선. 그룹 머리 아이콘 아래에서 세로선이 내려와 app-3 줄에서
   꺾여 끝난다. 구성도의 그룹 상자를 표에서 다시 읽게 하는 장치이고 선 모양은 panels.module.css 가 그린다 */
const TREE: Partial<Record<RowId, "group" | "member" | "last">> = {
  asg: "group",
  app3: "last",
};

/**
 * 구성도와 같은 상태어를 관리 콘솔의 상태 칩 한 모양(§12-14 StateChip)에 싣는다. 형태가 색 없이도 상태를 가른다.
 * 중지는 빈 원, 미구축은 점선 원이다(둘 다 무채)
 */
const STATE_CHIP: Record<NodeState, { tone: ChipTone; shape: StatusShapeKind }> = {
  running: { tone: "success", shape: "circle" },
  stopped: { tone: "off", shape: "ring" },
  failure: { tone: "danger", shape: "square" },
  transition: { tone: "warning", shape: "triangle" },
  absent: { tone: "neutral", shape: "dashed" },
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
        const leader = (r.id === "api1" || r.id === "api2") && isLeader(state, r.id);
        return (
          <span className={styles.instNameCell} data-tree={TREE[r.id]}>
            <span
              className={styles.instName}
              data-leader={leader ? "true" : undefined}
              data-group={r.id === "asg" ? "true" : undefined}
            >
              <span className={styles.instGlyph}>
                <FlowIcon name={r.icon} size={16} />
              </span>
              <span className={styles.instNameText}>{viewOf(r.id).name}</span>
              {leader && <span className={styles.leaderTag}>리더</span>}
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
            <NodeStateChip state={v.state} />
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
        // 관제 값 모드의 DB·봇 줄은 관제 판의 호스트 CPU 다. 관측은 bot-1 호스트에서 돌아 시장 봇 줄과 같은 값이라 다시 적지 않는다
        if (state.live && (r.id === "pg1" || r.id === "pg2" || r.id === "bot")) {
          const host = state.live.hostCpu[r.id];
          return host === null ? (
            <span className={styles.instDim}>
              <span aria-hidden="true"><StatusShape kind="dashed" /> </span>
              미수집
            </span>
          ) : (
            <span className="num">{Math.round(host)}%</span>
          );
        }
        if (state.live && r.id === "obs") {
          return (
            <span className={styles.instDim} title="관측은 bot-1 호스트에서 돌아 CPU는 시장 봇 줄과 같습니다">
              bot-1
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
              <span aria-hidden="true"><StatusShape kind="dashed" /> </span>
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

/** 상태 칸. 칩은 관리 콘솔 한 벌이고, 감싼 칸의 data-state 는 미구축 줄의 이름 잉크를 내리는 표지다 */
function NodeStateChip({ state }: { state: NodeState }) {
  const chip = STATE_CHIP[state];
  return (
    <span className={styles.stateCell} data-state={state}>
      <StateChip tone={chip.tone} shape={chip.shape} label={STATE_WORD[state]} />
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
 * 실행과 전환 중 둘뿐이다. 역할 칸에는 그룹이 움직이는 app-3 과 앱 계층의 하한·상한을 적는다
 */
function groupView(a: AsgView): RowView {
  return {
    name: "Auto Scaling",
    container: ASG_CONTAINER,
    role: `app-3 · 앱 계층 min ${a.min} · max ${a.max}`,
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

/**
 * 관제 값 모드의 비고. 관제 판에서 읽히는 사실만 적는다. 판이 모르는 줄(CloudFront)은 목표 구성이라고만 적고,
 * 모드와 무관한 줄(매칭 노드, 봇, ASG)은 null 을 돌려 공통 비고를 쓴다
 */
function liveNote(s: FlowState, id: RowId): string | null {
  switch (id) {
    case "cdn":
      return "관제 판에 없음 · 목표 구성";
    case "alb": {
      const readers = APP_IDS.filter((a) => s.apps[a].inRead).length;
      return `쓰기 파티션 리더 · 조회 ${readers}대`;
    }
    case "app3":
      switch (s.apps[id].status) {
        case "stopped":
          return "앱 평균 CPU가 오르면 켜짐";
        case "booting":
          return "ALB 등록 대기";
        case "draining":
          return "ALB 제외 · 드레이닝";
        default:
          return "ALB 조회 대상";
      }
    case "pg1":
      return "쓰기 · 복제 원본";
    case "pg2":
      return s.replicating ? "비동기 복제 · 스트리밍" : "복제 끊김";
    case "obs":
      return "bot-1 · 수집과 대시보드";
    case "load":
      return "시험 노드 · 조회 부하";
    default:
      return null;
  }
}

/** 비고 열. 지금 상태에서 운영자가 다음에 볼 것, 또는 이 대역의 한계를 한 줄로 적는다 */
function noteText(s: FlowState, id: RowId, asg: AsgView): string {
  const live = s.live ? liveNote(s, id) : null;
  if (live !== null) return live;
  switch (id) {
    case "cdn":
      // 구성도는 목표 구성대로 켜진 노드로 그린다. 표는 이 대역에서 요청이 ALB 로 바로 들어간다는 사실을 남긴다
      return "Docker 대역에 없음 · 목표 구성";
    case "alb": {
      // 쓰기 업스트림은 nginx 설정에 고정돼 있다. 리더가 바뀌어도 app-1 을 먼저 두드리고 실패하면 app-2 로 넘긴다
      const readers = APP_IDS.filter((a) => s.apps[a].inRead).length;
      return `쓰기 ${APP_NAME.api1} 우선 · 조회 ${readers}대`;
    }
    case "api1":
    case "api2":
      return engineNote(s, id);
    case "app3":
      switch (s.apps.app3.status) {
        case "stopped":
          return "운영자 승인 뒤 조정기가 기동";
        case "booting":
          return "ALB 등록 대기";
        case "draining":
          return "ALB 제외 · 드레이닝 10초";
        default:
          // Prometheus 가 앱 지표를 긁는 대상은 app-1, app-2, 봇뿐이라 증설 노드의 앱 지표는 비어 있다.
          // 컨테이너 CPU 는 chaos-cstats 가 실행 중인 컨테이너 전부에서 내므로 app-3 도 받는다
          return "앱 지표 미수집 · CPU 는 cstats";
      }
    case "pg1":
      // DB 지표 수집기는 chaos-pg 한 대만 본다. db-standby 가 붙어도 지금 설정으로는 복제 상대의 지표가 없다
      return "DB 지표 chaos-pg-exporter";
    case "pg2":
      // 재생은 로컬 Docker 스택의 사실(Standby 컨테이너 없음)이다. 관제 값 모드는 liveNote 가 복제 상태를 적는다
      return "2단계 추가 · 비동기 복제 예정";
    case "bot":
      return "ALB 로 주문 · 봇 계정 DB 직결";
    case "obs":
      // 컨테이너 CPU 는 chaos-cstats 가 실행 중인 컨테이너 전부에서 내므로 빠지는 쪽(앱 지표)만 적는다
      return "앱 지표 app-1·app-2·봇만";
    case "load":
      return "호스트에서 ALB :58080 조회";
    case "asg":
      // 목표는 운영자가 승인한 앱 계층 희망 대수, 등록은 ALB 조회 대상에 든 앱 수다
      return `앱 목표 ${asg.desired} · 등록 ${asg.registered}`;
  }
}

/** 매칭 리더를 다투는 두 노드의 비고. 리스는 DB 의 leader_lease 에 있다 */
function engineNote(s: FlowState, id: "api1" | "api2"): string {
  const node = s.apps[id];
  if (s.live) {
    const parts = partitionsOf(s, id);
    if (parts.length === 0) return node.status === "running" ? "보유 파티션 없음" : "재기동 전까지 중단";
    return `${parts.map((p) => `${p.id} 리스 보유 · epoch ${p.epoch}`).join(" · ")}`;
  }
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
