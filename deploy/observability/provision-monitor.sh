#!/bin/sh
set -eu
umask 077
: "${DB_ADMIN_USER:?DB_ADMIN_USER is required}"
: "${DB_ADMIN_PASSWORD:?DB_ADMIN_PASSWORD is required}"
: "${PG_MONITOR_PASSWORD_FILE:?PG_MONITOR_PASSWORD_FILE is required}"
[ -f "$PG_MONITOR_PASSWORD_FILE" ] || { echo 'Monitor secret file is missing' >&2; exit 1; }
[ "$(wc -c < "$PG_MONITOR_PASSWORD_FILE")" -le 1024 ] || { echo 'Monitor secret file too large' >&2; exit 1; }
PG_MONITOR_PASSWORD=$(cat "$PG_MONITOR_PASSWORD_FILE")
[ "${#PG_MONITOR_PASSWORD}" -ge 24 ] || { echo 'Monitor password must contain at least 24 characters' >&2; exit 1; }
export PG_MONITOR_PASSWORD
export PGPASSWORD="$DB_ADMIN_PASSWORD"
exec psql -X --host "${DB_HOST:-127.0.0.1}" --port "${DB_PORT:-5432}" --username "$DB_ADMIN_USER" \
  --dbname "${DB_NAME:-exchange}" --file "$(dirname "$0")/provision-monitor.sql"
