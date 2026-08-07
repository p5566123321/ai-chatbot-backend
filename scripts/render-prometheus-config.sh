#!/usr/bin/env bash
# Renders observability/prometheus/prometheus.yml from its .template, substituting the app's
# actual port so Prometheus scrapes the right target. Prometheus's config format has no env-var
# substitution of its own, and Docker Compose only substitutes variables inside the compose file
# itself, not inside files it mounts — so this plain sed step stands in for both.
#
# Port precedence: explicit arg > $SERVER_PORT (e.g. after `source scripts/load-env.sh`) > 8080.
# The generated prometheus.yml is gitignored; re-run this whenever SERVER_PORT changes.
#
# Usage: ./scripts/render-prometheus-config.sh [port]

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
template="$script_dir/../observability/prometheus/prometheus.yml.template"
out_file="$script_dir/../observability/prometheus/prometheus.yml"

port="${1:-${SERVER_PORT:-8080}}"

sed "s/__APP_PORT__/$port/" "$template" > "$out_file"

echo "Rendered $out_file (target port $port)"
