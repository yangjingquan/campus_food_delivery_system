#!/usr/bin/env bash
set -Eeuo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Run as root on production host." >&2; exit 1; }

ROOT=/opt/campus-food
SHOP=/opt/shop
WB=$SHOP/workbench
SC=$SHOP/docker-compose.yml
SE=$SHOP/.env
WC=$WB/docker-compose.yml
WE=$WB/.env
ACL=$ROOT/secrets/redis-users.acl
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACK=/root/backups/campus-food/redis-rotation-$STAMP
umask 077
install -d -m 700 "$BACK"
cp -a "$SC" "$BACK/shop-compose"
cp -a "$SE" "$BACK/shop-env"
cp -a "$WC" "$BACK/workbench-compose"
cp -a "$WE" "$BACK/workbench-env"
if [ -e "$ACL" ]; then cp -a "$ACL" "$BACK/acl"; touch "$BACK/acl-existed"; fi

get_env() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import sys
p,k=Path(sys.argv[1]),sys.argv[2]
v=[x.split("=",1)[1] for x in p.read_text().splitlines() if x.startswith(k+"=")]
if len(v)!=1 or not v[0]: raise SystemExit("Required environment entry is missing.")
print(v[0])
PY
}
OLD=$(get_env "$SE" REDIS_PASSWORD)
NEW=$(openssl rand -hex 32)
CFD_PASS=$(get_env "$ROOT/.env" REDIS_CFD_PASSWORD)
export NEW

rollback() {
  rc=$?
  trap - ERR
  echo "Redis rotation failed; restoring saved configuration and clients." >&2
  set +e
  if docker inspect shop-redis >/dev/null 2>&1; then
    docker inspect shop-redis --format '{{json .State.Health.Log}}' > "$BACK/failed-health.json" 2>/dev/null
    docker logs --tail 80 shop-redis > "$BACK/failed-redis.log" 2>&1
    chmod 600 "$BACK/failed-health.json" "$BACK/failed-redis.log"
  fi
  cp -a "$BACK/shop-compose" "$SC"
  cp -a "$BACK/shop-env" "$SE"
  cp -a "$BACK/workbench-compose" "$WC"
  cp -a "$BACK/workbench-env" "$WE"
  if [ -f "$BACK/acl-existed" ]; then cp -a "$BACK/acl" "$ACL"; else rm -f "$ACL"; fi
  docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 120 redis
  docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 240 shop-admin-app
  docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 240 shop-wx-app
  docker compose --project-directory "$WB" --project-name workbench --env-file "$WE" -f "$WC" up -d --no-deps --force-recreate --wait --wait-timeout 240 workbench-api
  exit "$rc"
}
trap rollback ERR

# Snapshot Redis data and every config that will change.
docker exec -e REDISCLI_AUTH="$OLD" shop-redis redis-cli SAVE | grep -qx OK
docker cp shop-redis:/data/dump.rdb "$BACK/redis-dump.rdb"
chmod 600 "$BACK/redis-dump.rdb"

set_env() {
  export CFG_PATH=$1 CFG_KEY=$2 CFG_VALUE=$3
  python3 <<'PY'
from pathlib import Path
import os,stat
p=Path(os.environ["CFG_PATH"]); k=os.environ["CFG_KEY"]; v=os.environ["CFG_VALUE"]
lines=p.read_text().splitlines()
ix=[i for i,x in enumerate(lines) if x.startswith(k+"=")]
if len(ix)>1: raise SystemExit("Duplicate environment key.")
if ix: lines[ix[0]]=k+"="+v
else: lines.append(k+"="+v)
t=p.with_name(p.name+".new"); t.write_text("\n".join(lines)+"\n")
os.chmod(t,stat.S_IMODE(p.stat().st_mode)); os.replace(t,p)
PY
  unset CFG_PATH CFG_KEY CFG_VALUE
}
set_env "$SE" REDIS_PASSWORD "$NEW"
set_env "$WE" REDIS_PASSWORD "$NEW"

python3 - "$ACL" "$ROOT/.env" <<'PY'
from pathlib import Path
import hashlib,os,sys
acl_path,env_path=Path(sys.argv[1]),Path(sys.argv[2])
env={}
for line in env_path.read_text().splitlines():
 k,sep,v=line.partition("=")
 if sep: env[k]=v
app=env.get("REDIS_CFD_PASSWORD",""); default=os.environ.get("NEW","")
if not app or not default: raise SystemExit("Redis credentials missing.")
h=lambda s: hashlib.sha256(s.encode()).hexdigest()
text=(f"user default reset on #{h(default)} ~* &* +@all\n"
      f"user cfd-app reset on #{h(app)} ~cfd:* &cfd:* +get +set +del +unlink +expire +pexpire +ttl +pttl +exists +ping +select +info +client|setname +client|setinfo +hello\n")
tmp=acl_path.with_name("."+acl_path.name+".new"); tmp.write_text(text)
os.chmod(tmp,0o644); os.replace(tmp,acl_path)
PY

python3 - "$SC" "$ACL" <<'PY'
from pathlib import Path
import json,sys,yaml
p,acl=Path(sys.argv[1]),sys.argv[2]; text=p.read_text(); doc=yaml.safe_load(text)
svc=doc.get("services",{}).get("redis")
if not isinstance(svc,dict) or not isinstance(svc.get("command"),list): raise SystemExit("Unexpected Redis Compose format.")
cmd=svc["command"]; out=[]; i=0
while i<len(cmd):
 if cmd[i] in ("--requirepass","--aclfile"): i+=2; continue
 out.append(cmd[i]); i+=1
out += ["--aclfile","/run/redis-users.acl"]
mount=f"{acl}:/run/redis-users.acl:ro"
if mount not in svc.get("volumes",[]): svc["volumes"].append(mount)
lines=text.splitlines(); start=next((i for i,x in enumerate(lines) if x=="  redis:"),None)
if start is None: raise SystemExit("Redis service block not found.")
end=next((i for i in range(start+1,len(lines)) if lines[i].startswith("  ") and not lines[i].startswith("    ") and lines[i].strip().endswith(":")),len(lines))
block=lines[start:end]
ci=next((i for i,x in enumerate(block) if x.startswith("    command:")),None)
vi=next((i for i,x in enumerate(block) if x.startswith("    volumes:")),None)
if ci is None or vi is None: raise SystemExit("Redis command or volume entry missing.")
block[ci]="    command: "+json.dumps(out)
if not any(mount in x for x in block): block.insert(vi+2,"      - "+mount)
lines[start:end]=block; updated="\n".join(lines)+"\n"; yaml.safe_load(updated); p.write_text(updated)
PY

python3 - "$WC" <<'PY'
from pathlib import Path
import re,sys,yaml
p=Path(sys.argv[1]); text=p.read_text(); doc=yaml.safe_load(text)
svc=doc.get("services",{}).get("workbench-api"); env=svc.get("environment",{}) if svc else {}
if not isinstance(env,dict) or "REDIS_URL" not in env: raise SystemExit("Workbench Redis URL not found.")
lines=text.splitlines(); start=next((i for i,x in enumerate(lines) if x=="  workbench-api:"),None)
if start is None: raise SystemExit("Workbench API service block not found.")
end=next((i for i in range(start+1,len(lines)) if lines[i].startswith("  ") and not lines[i].startswith("    ") and lines[i].strip().endswith(":")),len(lines))
ix=next((i for i in range(start+1,end) if re.match(r"^\s+REDIS_URL:",lines[i])),None)
if ix is None: raise SystemExit("Workbench REDIS_URL line not found.")
indent=lines[ix][:len(lines[ix])-len(lines[ix].lstrip())]
lines[ix]=indent+"REDIS_URL: redis://default:${REDIS_PASSWORD}@shop-redis:6379/0"
updated="\n".join(lines)+"\n"; yaml.safe_load(updated); p.write_text(updated)
PY

docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" config -q
docker compose --project-directory "$WB" --project-name workbench --env-file "$WE" -f "$WC" config -q
cp -a "$SE" "$BACK/shop-env-candidate"
cp -a "$WE" "$BACK/workbench-env-candidate"
cp -a "$SC" "$BACK/shop-compose-candidate"
cp -a "$WC" "$BACK/workbench-compose-candidate"
cp -a "$ACL" "$BACK/acl-candidate"
chmod 600 "$BACK"/*-candidate "$BACK/acl-candidate"
docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 120 redis

docker exec -e REDISCLI_AUTH="$NEW" shop-redis redis-cli ping | grep -qx PONG
docker exec -e REDISCLI_AUTH="$CFD_PASS" shop-redis redis-cli --user cfd-app ping | grep -qx PONG
old_result=$(docker exec -e REDISCLI_AUTH="$OLD" shop-redis redis-cli ping 2>/dev/null || true)
[ "$old_result" != PONG ]

docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 240 shop-admin-app
docker compose --project-directory "$SHOP" --project-name shop --env-file "$SE" -f "$SC" up -d --no-deps --force-recreate --wait --wait-timeout 240 shop-wx-app
docker compose --project-directory "$WB" --project-name workbench --env-file "$WE" -f "$WC" up -d --no-deps --force-recreate --wait --wait-timeout 240 workbench-api

trap - ERR
unset NEW OLD CFD_PASS
echo "Redis password rotated, cfd-app ACL installed, and Redis clients recreated."
echo "Rollback snapshots are stored in $BACK"
