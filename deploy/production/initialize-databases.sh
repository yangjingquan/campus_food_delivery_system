#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

ROOT=/opt/campus-food
ENV_FILE="$ROOT/.env"
SCHEMA_FILE="$ROOT/nacos-3.1.1-mysql-schema.sql"
MARKER="$ROOT/.database-initialized"
test -s "$ENV_FILE"
test ! -e "$MARKER" || { echo "Project databases were already initialized."; exit 0; }

set -a
# The provision script writes only shell-safe random hex/base64 values here.
. "$ENV_FILE"
set +a

SQL_FILE=$(mktemp "$ROOT/.database-init.XXXXXX.sql")
trap 'rm -f "$SQL_FILE"' EXIT
chmod 600 "$SQL_FILE"
cat > "$SQL_FILE" <<SQL
CREATE DATABASE IF NOT EXISTS campus_food_delivery CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS campus_food_nacos CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'cfd_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'cfd_nacos'@'%' IDENTIFIED BY '${NACOS_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON campus_food_delivery.* TO 'cfd_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX ON campus_food_nacos.* TO 'cfd_nacos'@'%';
SQL

docker inspect shop-mysql >/dev/null
docker exec -i shop-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4' < "$SQL_FILE"

table_count=$(docker exec shop-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=0x63616d7075735f666f6f645f6e61636f73"')
if [ "$table_count" -eq 0 ]; then
  curl -fsSL --retry 3 --connect-timeout 10 \
    https://raw.githubusercontent.com/alibaba/nacos/3.1.1/distribution/conf/mysql-schema.sql \
    -o "$SCHEMA_FILE"
  grep -q 'CREATE TABLE' "$SCHEMA_FILE"
  docker exec -i shop-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot campus_food_nacos --default-character-set=utf8mb4' < "$SCHEMA_FILE"
elif [ ! -s "$SCHEMA_FILE" ]; then
  echo "Nacos database already has tables but the versioned schema file is missing; refusing to guess its schema." >&2
  exit 1
fi
chmod 600 "$SCHEMA_FILE"

touch "$MARKER"
chmod 600 "$MARKER"
echo "Created the isolated application and Nacos databases and their restricted accounts."
echo "The existing shared Redis still needs a persistent cfd-app ACL user before admin-api starts."
