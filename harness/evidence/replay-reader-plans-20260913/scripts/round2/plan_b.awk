# usage: awk -v f=<name> -f plan_b.awk <file>
# 첫 계획의 최상위 비용·행 추정, 커서 노드와 ids UNION 추정, JIT 발화와 시간(3회 중앙값), 실행 시간 중앙값.
BEGIN { done = 0; n = 0; nj = 0; top = ""; toprows = ""; cur = ""; ids = ""; opts = ""; counts = "" }
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
  if (line ~ /Function Scan on (jsonb_each_text|unnest)/) {
    x = line; sub(/.*Function Scan on /, "", x); sub(/ .*/, "", x)
    y = line; sub(/.*cost=[^ ]* rows=/, "", y); sub(/ .*/, "", y)
    cur = cur " " x ":" y
  }
  if (line ~ /CTE Scan on cursors/) {
    y = line; sub(/.*cost=[^ ]* rows=/, "", y); sub(/ .*/, "", y); cur = cur " cte_scan:" y
  }
  if (ids == "" && line ~ /HashAggregate  \(/) { y = line; sub(/.*cost=[^ ]* rows=/, "", y); sub(/ .*/, "", y); ids = y }
  if (line ~ /Options: /) { o = line; sub(/.*Options: /, "", o); gsub(/ /, "", o); opts = o }
}
END {
  for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (t[j] < t[i]) { tmp = t[i]; t[i] = t[j]; t[j] = tmp }
  for (i = 1; i <= nj; i++) for (j = i + 1; j <= nj; j++) if (jt[j] < jt[i]) { tmp = jt[i]; jt[i] = jt[j]; jt[j] = tmp }
  med = "NA"; if (n >= 3) med = t[2]; else if (n > 0) med = t[1]
  jmed = "none"; if (nj >= 3) jmed = jt[2]; else if (nj > 0) jmed = jt[1]
  runs = ""; for (i = 1; i <= n; i++) runs = runs " " t[i]
  printf "%s|exec_med=%s|runs=%s|topcost=%s|floors_est=%s|ids_union_est=%s|cursor_est=%s|jit_runs=%d|jit_total_med=%s|%s|%s\n", f, med, runs, top, toprows, ids, cur, nj, jmed, opts, counts
}
