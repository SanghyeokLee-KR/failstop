#!/bin/sh
# usage: apply.sh FROM TO   (Flyway 와 같게 파일마다 트랜잭션 하나)
set -eu
FROM=$1
TO=$2
ls /mig | sed -n 's/^V\([0-9][0-9]*\)__.*\.sql$/\1 &/p' | sort -n | while read v f; do
  if [ "$v" -ge "$FROM" ] && [ "$v" -le "$TO" ]; then
    echo "== $f"
    psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1 -1 -f "/mig/$f" > /dev/null
  fi
done
