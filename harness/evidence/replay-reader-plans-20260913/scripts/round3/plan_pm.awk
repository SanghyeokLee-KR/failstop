# usage: awk -v f=<name> -f plan_pm.awk <file>
# 첫 계획의 최상위 비용·행 추정, 구멍 탐색 스캔 추정/실제 행, 이벤트 탐색 횟수, JIT 발화 횟수와 시간 중앙값, 실행 시간 중앙값.
BEGIN { done = 0; n = 0; nj = 0; top = ""; toprows = ""; hole = ""; probes = ""; counts = "" }
/Execution Time:/ {
  v = $0; sub(/.*Execution Time: /, "", v); sub(/ ms.*/, "", v)
  n++; t[n] = v + 0; done = 1; next
}
/Timing: Generation/ { x = $0; sub(/.*Total /, "", x); sub(/ ms.*/, "", x); nj++; jt[nj] = x + 0 }
/COUNTS/ { c = $0; gsub(/ +/, "", c); counts = c; next }
done == 0 {
  line = $0
  if (top == "" && line ~ /cost=/) {
    x = line; sub(/.*cost=[0-9.]*\.\./, "", x); sub(/ .*/, "", x); top = x
    y = line; sub(/.*cost=[^ ]* rows=/, "", y); sub(/ .*/, "", y); toprows = y
  }
  if (hole == "" && line ~ /Scan using command_journal_p0_market_id_seq_idx on command_journal_p0 c(_[0-9]+)? /) {
    y = line; sub(/.*cost=[^ ]* rows=/, "", y); sub(/ .*/, "", y)
    a = "never"
    if (line ~ /actual time=/) { a = line; sub(/.*actual time=[^ ]* rows=/, "", a); sub(/ .*/, "", a) }
    hole = "est" y "/act" a
  }
  if (probes == "" && line ~ /Scan using event_journal_p0_source_cmd_seq_idx/) {
    p = "never"
    if (line ~ /loops=/) { p = line; sub(/.*loops=/, "", p); sub(/\).*/, "", p) }
    probes = p
  }
}
END {
  for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (t[j] < t[i]) { tmp = t[i]; t[i] = t[j]; t[j] = tmp }
  for (i = 1; i <= nj; i++) for (j = i + 1; j <= nj; j++) if (jt[j] < jt[i]) { tmp = jt[i]; jt[i] = jt[j]; jt[j] = tmp }
  med = "NA"; if (n >= 3) med = t[2]; else if (n > 0) med = t[1]
  jmed = "none"; if (nj >= 3) jmed = jt[2]; else if (nj > 0) jmed = jt[1]
  runs = ""; for (i = 1; i <= n; i++) runs = runs " " t[i]
  printf "%s|exec_med=%s|runs=%s|topcost=%s|toprows=%s|hole_scan=%s|evt_probes=%s|jit_runs=%d|jit_total_med=%s|%s\n", f, med, runs, top, toprows, hole, probes, nj, jmed, counts
}
