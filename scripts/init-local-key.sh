#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p .local
if [ -e .local/wechat-master.key ]; then
  printf '%s\n' '密钥文件已存在，不会覆盖。' >&2
  exit 1
fi
openssl rand -base64 32 > .local/wechat-master.key
printf '%s\n' '已生成本地加密主密钥。若数据库已有微信密文，请恢复原密钥，勿使用新密钥。'
