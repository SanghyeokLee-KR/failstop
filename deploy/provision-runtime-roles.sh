#!/bin/sh
set -eu
umask 077
: "${DB_HOST:?DB_HOST is required}"
: "${DB_ADMIN_USER:?DB_ADMIN_USER is required}"
: "${DB_ADMIN_PASSWORD:?DB_ADMIN_PASSWORD is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${READ_DB_USER:?READ_DB_USER is required}"
: "${READ_DB_PASSWORD:?READ_DB_PASSWORD is required}"
: "${BOT_DB_USER:?BOT_DB_USER is required}"
: "${BOT_DB_PASSWORD:?BOT_DB_PASSWORD is required}"
for runtime_user in "$DB_USER" "$READ_DB_USER" "$BOT_DB_USER"; do
  case "$runtime_user" in ''|[0-9]*|*[!a-zA-Z0-9_]*) echo "invalid runtime role name" >&2; exit 1;; esac
  if [ "$runtime_user" = "$DB_ADMIN_USER" ] || [ "$runtime_user" = app_role ] || [ "$runtime_user" = harness_role ]; then
    echo "runtime login must differ from schema owner and group roles" >&2
    exit 1
  fi
done
if [ "$DB_USER" = "$READ_DB_USER" ] || [ "$BOT_DB_USER" = "$READ_DB_USER" ] || [ "$DB_USER" = "$BOT_DB_USER" ]; then
  echo "runtime logins must be distinct" >&2
  exit 1
fi
export DB_USER DB_PASSWORD READ_DB_USER READ_DB_PASSWORD BOT_DB_USER BOT_DB_PASSWORD
export PGPASSWORD="$DB_ADMIN_PASSWORD"
exec psql -X --host "$DB_HOST" --port "${DB_PORT:-5432}" --username "$DB_ADMIN_USER" \
  --dbname "${DB_NAME:-exchange}" --file "$(dirname "$0")/provision-runtime-roles.sql"
