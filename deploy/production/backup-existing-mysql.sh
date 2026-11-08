#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root on the production host." >&2
  exit 1
fi

BACKUP_DIR=/root/backups/campus-food
install -d -m 700 "$BACKUP_DIR"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUP_DIR/mysql-all-$timestamp.sql.gz"
tmp="$backup.tmp"
trap 'rm -f "$tmp"' EXIT
umask 077

docker exec shop-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --all-databases --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF' \
  | gzip -1 > "$tmp"
test -s "$tmp"
mv "$tmp" "$backup"
chmod 600 "$backup"
du -h "$backup" | awk '{print "Created restricted MySQL backup: " $1}'
