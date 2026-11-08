#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

ROOT=/opt/campus-food
SECRETS="$ROOT/secrets"
umask 077
install -d -m 700 "$ROOT" "$SECRETS" "$ROOT/deploy/production"
cp -a deploy/production/. "$ROOT/deploy/production/"
chmod -R go-rwx "$ROOT/deploy"
install -d -o 1000 -g 1000 -m 750 "$ROOT/nacos/data" "$ROOT/nacos/logs"
install -m 600 deploy/production/compose.production.yml "$ROOT/compose.production.yml"

if [ ! -s "$ROOT/.env" ]; then
  cat > "$ROOT/.env" <<EOF
ADMIN_API_TAG=latest
USER_API_TAG=latest
GATEWAY_TAG=latest
ADMIN_WEB_TAG=latest
ADMIN_USER=admin
ADMIN_BOOTSTRAP_PASSWORD=$(openssl rand -hex 24)
APP_DB_PASSWORD=$(openssl rand -hex 32)
NACOS_DB_PASSWORD=$(openssl rand -hex 32)
NACOS_USERNAME=nacos
NACOS_PASSWORD=$(openssl rand -hex 32)
NACOS_AUTH_TOKEN=$(openssl rand -base64 48 | tr -d '\n')
NACOS_AUTH_IDENTITY_KEY=campus_food_identity
NACOS_AUTH_IDENTITY_VALUE=$(openssl rand -hex 24)
REDIS_CFD_PASSWORD=$(openssl rand -hex 32)
INTERNAL_API_KEY=$(openssl rand -hex 32)
WECHAT_USER_APP_ID=wxb2ef21a4d9800f4e
WECHAT_USER_APP_SECRET=CONFIGURE_WITH_WECHAT_APP_SECRET
EOF
  chmod 600 "$ROOT/.env"
fi

if [ ! -s "$SECRETS/wechat-master.key" ]; then
  openssl rand -base64 32 | tr -d '\n' > "$SECRETS/wechat-master.key"
  chmod 600 "$SECRETS/wechat-master.key"
fi

for required in shop-mysql shop-redis shop-nginx ai-shop-jenkins-proxy; do
  docker inspect "$required" >/dev/null
done

for network in campus-food-backend campus-food-proxy; do
  docker network inspect "$network" >/dev/null 2>&1 || docker network create --driver bridge "$network" >/dev/null
done

for container in shop-mysql shop-redis; do
  if ! docker inspect "$container" --format '{{json .NetworkSettings.Networks}}' | grep -q 'campus-food-backend'; then
    docker network connect campus-food-backend "$container"
  fi
done

if ! docker inspect shop-nginx --format '{{json .NetworkSettings.Networks}}' | grep -q 'campus-food-proxy'; then
  docker network connect campus-food-proxy shop-nginx
fi

echo "Created the private campus-food Docker networks and attached existing dependencies."
echo "Review /opt/campus-food/.env and install required certificate/key files before starting services."
echo "Redis ACL setup requires a persistent ACL file and a planned Redis restart; the script does not restart Redis."
