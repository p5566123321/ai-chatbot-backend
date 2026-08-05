#!/usr/bin/env bash
# Export every KEY=VALUE line from .env into the current shell so
# Spring can resolve ${...} placeholders in application.yaml.
# Usage: source scripts/load-env.sh [path-to-env-file]

set -u

env_file="${1:-.env}"

if [[ ! -f "$env_file" ]]; then
    echo "load-env: $env_file not found" >&2
    return 1 2>/dev/null || exit 1
fi

while IFS='=' read -r key value || [[ -n "$key" ]]; do
    key="$(echo "$key" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

    [[ -z "$key" || "$key" == \#* ]] && continue
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    value="$(echo "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    value="${value%\"}"
    value="${value#\"}"

    export "$key=$value"
done < "$env_file"

echo "load-env: exported vars from $env_file"
