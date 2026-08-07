#!/usr/bin/env bash
# Shared by seed-benchmark-data.sh / cleanup-benchmark-data.sh. Derives PGHOST/PGPORT/PGDATABASE/
# PGUSER/PGPASSWORD from the app's own DB_URL/DB_USER/DB_PASSWORD (see .env) rather than the
# docker-compose.yml POSTGRES_* defaults — those two don't necessarily point at the same database,
# and the whole point of this benchmark is to hit exactly what the app itself queries.
#
# Source this after `source scripts/load-env.sh`; explicit PGHOST/PGPORT/... in the environment
# still win, for anyone who wants to point the benchmark somewhere else on purpose.

: "${DB_URL:?DB_URL not set — run 'source scripts/load-env.sh' first}"
: "${DB_USER:?DB_USER not set — run 'source scripts/load-env.sh' first}"
: "${DB_PASSWORD:?DB_PASSWORD not set — run 'source scripts/load-env.sh' first}"

if [[ "$DB_URL" =~ ^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+) ]]; then
  PGHOST="${PGHOST:-${BASH_REMATCH[1]}}"
  PGPORT="${PGPORT:-${BASH_REMATCH[2]}}"
  PGDATABASE="${PGDATABASE:-${BASH_REMATCH[3]}}"
else
  echo "seed/cleanup: could not parse DB_URL='$DB_URL' (expected jdbc:postgresql://host:port/db)" >&2
  exit 1
fi

PGUSER="${PGUSER:-$DB_USER}"
PGPASSWORD="${PGPASSWORD:-$DB_PASSWORD}"
export PGPASSWORD
