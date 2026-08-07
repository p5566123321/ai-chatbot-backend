#!/usr/bin/env bash
# Removes everything seed-benchmark-data.sh created (uuid prefix 'bench-') and the id file it wrote.
#
# Usage: ./scripts/cleanup-benchmark-data.sh

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_pg-conn.sh
source "$script_dir/_pg-conn.sh"

psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -v ON_ERROR_STOP=1 -q <<SQL
DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE uuid LIKE 'bench-%');
DELETE FROM conversations WHERE uuid LIKE 'bench-%';
SQL

rm -f "$script_dir/../benchmark/conversation-ids.txt"

echo "Benchmark data cleaned up."
