#!/usr/bin/env bash
set -euo pipefail

ROOT=/opt/campus-food
test -s "$ROOT/.env"
docker compose --project-directory "$ROOT" --project-name campus-food \
  --env-file "$ROOT/.env" -f "$ROOT/compose.production.yml" \
  up -d --wait --wait-timeout 600 nacos
bash "$ROOT/deploy/production/initialize-nacos-admin.sh"
