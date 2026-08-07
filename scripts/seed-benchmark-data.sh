#!/usr/bin/env bash
# Seeds N conversations with M messages each directly into Postgres, bypassing the chat/LLM
# pipeline entirely, so the Redis-vs-DB benchmark only exercises ConversationHistoryService.getHistory
# and never calls out to Gemini. Seeded rows are tagged with a 'bench-' uuid prefix so they're
# easy to find and clean up (see cleanup-benchmark-data.sh).
#
# Writes the seeded conversation UUIDs to benchmark/conversation-ids.txt for the k6 script to replay.
#
# Usage: ./scripts/seed-benchmark-data.sh [conversation_count] [messages_per_conversation]

set -euo pipefail

CONVERSATIONS="${1:-200}"
MESSAGES_PER_CONVERSATION="${2:-10}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_pg-conn.sh
source "$script_dir/_pg-conn.sh"
out_file="$script_dir/../benchmark/conversation-ids.txt"

echo "Seeding $CONVERSATIONS conversations x $MESSAGES_PER_CONVERSATION messages into $PGDATABASE@$PGHOST:$PGPORT..."

psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO conversations (uuid, created_at)
SELECT 'bench-' || gen_random_uuid(), now()
FROM generate_series(1, $CONVERSATIONS);
SQL

psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO messages (conversation_id, role, content, created_at)
SELECT c.id,
       CASE WHEN m % 2 = 1 THEN 'USER' ELSE 'ASSISTANT' END,
       'benchmark message #' || m || ' — ' || repeat('lorem ipsum ', 20),
       now()
FROM conversations c
CROSS JOIN generate_series(1, $MESSAGES_PER_CONVERSATION) AS m
WHERE c.uuid LIKE 'bench-%';
SQL

psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -t -A -c \
  "SELECT uuid FROM conversations WHERE uuid LIKE 'bench-%' ORDER BY id" > "$out_file"

echo "Wrote $(wc -l < "$out_file" | tr -d ' ') conversation ids to $out_file"
