#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

ROOT=/opt/campus-food
COMPOSE=/opt/shop/docker-compose.yml
ACL_FILE="$ROOT/secrets/redis-users.acl"
BACKUP=/opt/shop/docker-compose.yml.pre-cfd-acl
set -a
. "$ROOT/.env"
set +a

if [ -s "$ACL_FILE" ] && grep -q '^user cfd-app ' "$ACL_FILE"; then
  echo "The persistent cfd-app Redis ACL already exists."
  exit 0
fi

redis_meta=$(python3 - <<'PY'
import json, subprocess
d=json.loads(subprocess.check_output(["docker","inspect","shop-redis"]))[0]
cmd=d["Config"].get("Cmd",[])
password=None
for i,arg in enumerate(cmd[:-1]):
    if arg.lower() in ("--requirepass","requirepass"):
        password=cmd[i+1]
        break
service=d["Config"].get("Labels",{}).get("com.docker.compose.service")
if not password or not service:
    raise SystemExit("Cannot identify the current Redis password argument and Compose service.")
print(json.dumps({"password":password,"service":service}))
PY
)
REDIS_CURRENT_PASSWORD=$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["password"])' <<< "$redis_meta")
REDIS_SERVICE=$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["service"])' <<< "$redis_meta")
export REDIS_CURRENT_PASSWORD

acl_list=$(docker exec -e REDISCLI_AUTH="$REDIS_CURRENT_PASSWORD" shop-redis redis-cli --raw ACL LIST)
default_acl=$(printf '%s\n' "$acl_list" | sed -n '/^user default /p')
test -n "$default_acl" || { echo "Could not read the existing Redis default-user ACL." >&2; exit 1; }
app_hash=$(printf '%s' "$REDIS_CFD_PASSWORD" | sha256sum | awk '{print $1}')

install -d -m 700 "$ROOT/secrets"
acl_tmp=$(mktemp "$ROOT/secrets/.redis-users.XXXXXX")
printf '%s\nuser cfd-app on #%s ~cfd:* &cfd:* +get +set +del +unlink +expire +pexpire +ttl +pttl +exists +ping +select +info +client|setname +client|setinfo +hello\n' \
  "$default_acl" "$app_hash" > "$acl_tmp"
chmod 644 "$acl_tmp"
mv "$acl_tmp" "$ACL_FILE"

if [ ! -e "$BACKUP" ]; then
  cp -a "$COMPOSE" "$BACKUP"
fi

python3 - "$COMPOSE" <<'PY'
from pathlib import Path
import json, sys, yaml

path=Path(sys.argv[1])
text=path.read_text()
lines=text.splitlines()
try:
    start=lines.index("  redis:")
except ValueError:
    raise SystemExit("Redis service block was not found in the shop Compose file.")
end=next((i for i in range(start+1,len(lines)) if lines[i].startswith("  ") and not lines[i].startswith("    ") and lines[i].strip().endswith(":")),len(lines))
block=lines[start:end]
if any("/data/redis-users.acl" in line for line in block):
    raise SystemExit("Redis ACL mount already exists; inspect the Compose file before continuing.")
cmd_i=next((i for i,line in enumerate(block) if line.startswith("    command:")),None)
vol_i=next((i for i,line in enumerate(block) if line.startswith("    volumes:")),None)
if cmd_i is None or vol_i is None:
    raise SystemExit("Expected one-line command and volumes entries; refusing to rewrite an unfamiliar Redis service.")
prefix="    command:"
raw=block[cmd_i][len(prefix):].strip()
command=json.loads(raw)
if not isinstance(command,list):
    raise SystemExit("Expected the Redis command as a YAML/JSON argument list.")
if "--aclfile" not in command:
    command.extend(["--aclfile","/data/redis-users.acl"])
block[cmd_i]=prefix+" "+json.dumps(command)
block.insert(vol_i+1,"      - /opt/campus-food/secrets/redis-users.acl:/data/redis-users.acl:ro")
lines[start:end]=block
new_text="\n".join(lines)+"\n"
yaml.safe_load(new_text)
path.write_text(new_text)
PY

if ! REDIS_CURRENT_PASSWORD="$REDIS_CURRENT_PASSWORD" docker compose \
  --project-directory /opt/shop --project-name shop \
  -f "$COMPOSE" up -d --no-deps --wait --wait-timeout 120 "$REDIS_SERVICE"; then
  cp -a "$BACKUP" "$COMPOSE"
  REDIS_CURRENT_PASSWORD="$REDIS_CURRENT_PASSWORD" docker compose \
    --project-directory /opt/shop --project-name shop \
    -f "$COMPOSE" up -d --no-deps --wait --wait-timeout 120 "$REDIS_SERVICE" || true
  echo "Redis did not become healthy; restored the original Compose file." >&2
  exit 1
fi

docker exec -e REDISCLI_AUTH="$REDIS_CFD_PASSWORD" shop-redis redis-cli --user cfd-app ping | grep -qx PONG
docker exec -e REDISCLI_AUTH="$REDIS_CURRENT_PASSWORD" shop-redis redis-cli ping | grep -qx PONG
echo "Redis is back online with its existing default account and the restricted cfd-app account."
