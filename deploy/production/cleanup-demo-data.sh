#!/usr/bin/env bash
set -euo pipefail

ROOT=/opt/campus-food
MARKER="$ROOT/.demo-data-cleaned"
if [ -e "$MARKER" ]; then
  echo "Development seed rows were already removed."
  exit 0
fi

docker inspect campus-food-admin-api >/dev/null
docker exec -i shop-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot campus_food_delivery --default-character-set=utf8mb4' \
  < "$ROOT/deploy/production/cleanup-demo-data.sql"
touch "$MARKER"
chmod 600 "$MARKER"
echo "Removed only the known local merchant and rider fixtures from the new production database."
