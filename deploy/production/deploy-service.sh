#!/bin/sh
set -eu

: "${SERVICE_NAME:?missing SERVICE_NAME}"
: "${SERVICE_TAG:?missing SERVICE_TAG}"

case "$SERVICE_NAME" in
  gateway) tag_variable=GATEWAY_TAG ;;
  admin-api) tag_variable=ADMIN_API_TAG ;;
  user-api) tag_variable=USER_API_TAG ;;
  merchant-api) tag_variable=MERCHANT_API_TAG ;;
  rider-api) tag_variable=RIDER_API_TAG ;;
  admin-web) tag_variable=ADMIN_WEB_TAG ;;
  *) echo "Unknown service: $SERVICE_NAME" >&2; exit 2 ;;
esac

lock=/prod/.deploy-lock
waited=0
until mkdir "$lock" 2>/dev/null; do
  [ "$waited" -lt 900 ] || { echo "deployment lock wait timed out" >&2; exit 1; }
  sleep 2
  waited=$((waited + 2))
done
trap 'rmdir "$lock" 2>/dev/null || true' EXIT HUP INT TERM

test -s /prod/.env

cp /src/deploy/production/compose.production.yml /prod/compose.production.yml.new
chmod 600 /prod/compose.production.yml.new
mv /prod/compose.production.yml.new /prod/compose.production.yml

compose() {
  docker compose --project-directory /prod --project-name campus-food \
    --env-file /prod/.env -f /prod/compose.production.yml "$@"
}
old_tag=$(sed -n "s/^${tag_variable}=//p" /prod/.env | tail -n 1)
export "$tag_variable=$SERVICE_TAG"
if ! compose up -d --no-deps --force-recreate --wait --wait-timeout 240 "$SERVICE_NAME"; then
  echo "Health check failed for $SERVICE_NAME; restoring the prior image tag." >&2
  if [ -n "$old_tag" ]; then
    export "$tag_variable=$old_tag"
    compose up -d --no-deps --wait --wait-timeout 240 "$SERVICE_NAME" || true
  fi
  exit 1
fi

tmp=$(mktemp /prod/.env.XXXXXX)
if grep -q "^${tag_variable}=" /prod/.env; then
  sed "s|^${tag_variable}=.*|${tag_variable}=${SERVICE_TAG}|" /prod/.env > "$tmp"
else
  cat /prod/.env > "$tmp"
  printf '\n%s=%s\n' "$tag_variable" "$SERVICE_TAG" >> "$tmp"
fi
chmod 600 "$tmp"
mv "$tmp" /prod/.env

docker image prune -af --filter "label=campus-food.service=$SERVICE_NAME" --filter until=720h >/dev/null 2>&1 || true
compose ps "$SERVICE_NAME"
