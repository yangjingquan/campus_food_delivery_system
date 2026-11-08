#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

ROOT=/opt/campus-food
SECRETS="$ROOT/secrets"
TLS=/etc/shop/tls
SHOP_CONF=/opt/shop/nginx/conf.d
HTTP_CONF=/opt/jenkins/nginx/conf.d
ADMIN_ZIP=${1:?pass the cfdadmin certificate archive}
API_ZIP=${2:?pass the cfdapi certificate archive}

install -d -m 700 "$SECRETS" "$TLS" "$SHOP_CONF" "$HTTP_CONF"

unzip -p "$ADMIN_ZIP" cfdadmin.nexbyte.top_nginx/cfdadmin.nexbyte.top_bundle.pem > "$TLS/cfdadmin.nexbyte.top_bundle.pem"
unzip -p "$ADMIN_ZIP" cfdadmin.nexbyte.top_nginx/cfdadmin.nexbyte.top.key > "$TLS/cfdadmin.nexbyte.top.key"
unzip -p "$API_ZIP" cfdapi.nexbyte.top_nginx/cfdapi.nexbyte.top_bundle.pem > "$TLS/cfdapi.nexbyte.top_bundle.pem"
unzip -p "$API_ZIP" cfdapi.nexbyte.top_nginx/cfdapi.nexbyte.top.key > "$TLS/cfdapi.nexbyte.top.key"
chmod 644 "$TLS/cfdadmin.nexbyte.top_bundle.pem" "$TLS/cfdapi.nexbyte.top_bundle.pem"
chmod 600 "$TLS/cfdadmin.nexbyte.top.key" "$TLS/cfdapi.nexbyte.top.key"
openssl x509 -in "$TLS/cfdadmin.nexbyte.top_bundle.pem" -noout -checkend 0
openssl x509 -in "$TLS/cfdapi.nexbyte.top_bundle.pem" -noout -checkend 0

install -m 644 deploy/production/cfdadmin.nginx.conf "$SHOP_CONF/cfdadmin.conf"
install -m 644 deploy/production/cfdapi.nginx.conf "$SHOP_CONF/cfdapi.conf"
install -m 644 deploy/production/campus-food-http.conf "$HTTP_CONF/campus-food.conf"

docker network inspect campus-food-proxy >/dev/null
docker inspect shop-nginx --format '{{json .NetworkSettings.Networks}}' | grep -q 'campus-food-proxy' || \
  docker network connect campus-food-proxy shop-nginx

docker cp "$SHOP_CONF/cfdadmin.conf" shop-nginx:/etc/nginx/conf.d/cfdadmin.conf
docker cp "$SHOP_CONF/cfdapi.conf" shop-nginx:/etc/nginx/conf.d/cfdapi.conf
docker exec shop-nginx nginx -t
docker exec shop-nginx nginx -s reload

docker exec ai-shop-jenkins-proxy nginx -t
docker exec ai-shop-jenkins-proxy nginx -s reload
echo "Installed the two HTTPS virtual hosts and HTTP redirects."
