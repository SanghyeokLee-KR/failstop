/**
 * 출처 칩. 무대 머리 줄 맨 왼쪽에서 화면 값이 어디서 왔는지를 알린다. 지금 값은 모두 재생이라 한 가지
 * 꼴만 있다. 사건의 순서와 간격은 로컬 Docker 측정 기록에서 오지만 사건 사이의 수치는 flowSim 이
 * 지어낸다. 화면에 보이는 시안 표식은 사용자 요청으로 뺐으므로(2026-09-23) 보이는 칩 문구는 출처만
 * 적고, 값이 실측으로 낭독되지 않게 시뮬레이션이라는 사실은 보조기술용 문구로 싣는다. 보이는 문구에
 * 이를 더할지는 사용자 결정 대기다. 실측 꼴('● 실측 · N초 전', 지연이면 ▲)은 백엔드 값을 연결할 때
 * 그 데이터 원천과 함께 만든다(관리자 설계 7-10 (i) 2).
 */

import styles from "./flow.module.css";

export default function SourceChip() {
  return (
    <span className={styles.sourceChip}>
      재생 · 로컬 측정 기록
      <span className="sr-only"> · 사건 순서는 측정 기록, 값은 시뮬레이션</span>
    </span>
  );
}
