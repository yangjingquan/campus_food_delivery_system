#!/usr/bin/env bash
set -euo pipefail

ROOT=/opt/campus-food
set -a
. "$ROOT/.env"
set +a

docker inspect campus-food-nacos >/dev/null
docker exec -e NCPASS="$NACOS_PASSWORD" campus-food-nacos bash -ec '
  if curl -fsS -o /dev/null -X POST http://127.0.0.1:8848/nacos/v3/auth/user/login \
    --data-urlencode "username=nacos" --data-urlencode "password=$NCPASS"; then
    exit 0
  fi
  curl -fsS -o /dev/null -X POST http://127.0.0.1:8848/nacos/v3/auth/user/admin \
    --data-urlencode "password=$NCPASS"
'
echo "Nacos administrator password is initialized from the root-only host environment file."
