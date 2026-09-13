#!/bin/sh
# /out/<state>/<combo>__<mode>.txt 에서 실행 시간 3회와 중앙값, 첫 계획의 노드 요약을 뽑는다.
set -eu
echo "state,combo,mode,median_ms,runs_ms,rows_top,buffers_top"
for f in /out/*/*.txt; do
  state=$(basename "$(dirname "$f")")
  b=$(basename "$f" .txt)
  case "$b" in auto__*) continue ;; esac
  combo=${b%__*}
  mode=${b##*__}
  runs=$(grep 'Execution Time' "$f" | sed 's/.*: \([0-9.]*\) ms/\1/' | sort -n | tr '\n' ' ')
  med=$(grep 'Execution Time' "$f" | sed 's/.*: \([0-9.]*\) ms/\1/' | sort -n | sed -n 2p)
  top=$(grep -m1 'actual time' "$f" | sed 's/.*actual time=[^ ]* rows=\([0-9]*\).*/\1/')
  buf=$(grep -m1 'Buffers:' "$f" | sed 's/^ *Buffers: //')
  echo "$state,$combo,$mode,$med,$runs,$top,\"$buf\""
done
