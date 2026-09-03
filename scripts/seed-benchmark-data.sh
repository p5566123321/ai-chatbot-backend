#!/usr/bin/env bash
# Seeds N conversations with M messages each directly into Postgres, bypassing the chat/LLM
# pipeline entirely, so the Redis-vs-DB benchmark only exercises ConversationHistoryService.getHistory
# and never calls out to Gemini. Seeded rows are tagged with a 'bench-' uuid prefix so they're
# easy to find and clean up (see cleanup-benchmark-data.sh).
#
# ADR-007 added per-owner conversation gating (ConversationService.requireOwnedConversation) after
# this script was first written: any conversation whose owner_id doesn't match the caller's JWT
# 404s, including owner_id IS NULL (never equals anyone's id). So seeded conversations must be
# owned by a real user, and the k6 script must authenticate as that same user — see
# BENCH_USER_EMAIL/BENCH_USER_PASSWORD below and setup() in k6-history-latency.js.
#
# Writes the seeded conversation UUIDs to benchmark/conversation-ids.txt for the k6 script to replay.
#
# Usage: ./scripts/seed-benchmark-data.sh [conversation_count] [messages_per_conversation]
#
# Env vars:
#   BASE_URL             default http://localhost:$SERVER_PORT (falls back to 8080) — used only to
#                         register/look up the benchmark user via the app's own auth API.
#   BENCH_USER_EMAIL      default bench@ai-chatbot.local
#   BENCH_USER_PASSWORD   default benchmark-password-123

set -euo pipefail

CONVERSATIONS="${1:-200}"
MESSAGES_PER_CONVERSATION="${2:-10}"
BASE_URL="${BASE_URL:-http://localhost:${SERVER_PORT:-8080}}"
BENCH_USER_EMAIL="${BENCH_USER_EMAIL:-bench@ai-chatbot.local}"
BENCH_USER_PASSWORD="${BENCH_USER_PASSWORD:-benchmark-password-123}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_pg-conn.sh
source "$script_dir/_pg-conn.sh"
out_file="$script_dir/../benchmark/conversation-ids.txt"

# Idempotent: 201 on first run, 409 (already exists) on every rerun — either way the user exists
# afterwards, so its exit code is ignored and the actual owner id is read back from Postgres.
echo "Ensuring benchmark user $BENCH_USER_EMAIL exists at $BASE_URL..."
curl -s -o /dev/null -X POST "$BASE_URL/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$BENCH_USER_EMAIL\",\"password\":\"$BENCH_USER_PASSWORD\"}" || true

OWNER_ID="$(psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -t -A -c \
  "SELECT id FROM users WHERE email = '$BENCH_USER_EMAIL'")"
if [[ -z "$OWNER_ID" ]]; then
  echo "seed: could not find/create benchmark user $BENCH_USER_EMAIL — is the app running at $BASE_URL?" >&2
  exit 1
fi
echo "Benchmark user id: $OWNER_ID"

echo "Seeding $CONVERSATIONS conversations x $MESSAGES_PER_CONVERSATION messages into $PGDATABASE@$PGHOST:$PGPORT..."

psql -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -U "$PGUSER" -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO conversations (uuid, owner_id, created_at)
SELECT 'bench-' || gen_random_uuid(), $OWNER_ID, now()
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
