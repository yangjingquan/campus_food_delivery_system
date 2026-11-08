#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -f .local/wechat-master.key ]; then
  printf '%s\n' '缺少微信加密主密钥。若数据库已有密文，请先恢复原密钥；新环境请先运行 scripts/init-local-key.sh。' >&2
  exit 1
fi
if [ ! -f .env ] || ! grep -Eq '^INTERNAL_API_KEY=.+$' .env; then
  umask 077
  temp_file=".env.tmp.$$"
  {
    if [ -f .env ]; then sed '/^INTERNAL_API_KEY=/d' .env; fi
    printf 'INTERNAL_API_KEY=%s\n' "$(openssl rand -hex 32)"
  } > "$temp_file"
  chmod 600 "$temp_file"
  mv "$temp_file" .env
  printf '%s\n' '已生成本地服务间通信密钥并保存在 .env。'
fi
docker compose up --build -d
