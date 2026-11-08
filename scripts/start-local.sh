#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f .local/wechat-master.key ]; then
  printf '%s\n' '缺少微信加密主密钥。若数据库已有密文，请先恢复原密钥；新环境请先运行 scripts/init-local-key.sh。' >&2
  exit 1
fi
docker compose up --build -d
