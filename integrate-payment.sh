#!/bin/bash
# payment-pro 双账本集成入驻（一次性）：注册商户 → 开户 → 拷贝 TLS 证书 → 生成集成配置。
# 前置：payment-pro 已 run.sh 启动（8080 控制台 + 8443 OpenAPI HTTPS）。
# 完成后重启 ride-pro（./run.sh）即启用资金镜像；未执行本脚本时 ride-pro 保持 standalone。
set -e
cd "$(dirname "$0")"

PAY_BASE="${PAY_BASE:-http://127.0.0.1:8080}"
TLS_BASE="${TLS_BASE:-https://127.0.0.1:8443}"
CALLBACK_BASE="${CALLBACK_BASE:-http://127.0.0.1:8090}"
PAY_ROOT="${PAY_ROOT:-../payment-pro}"

mkdir -p config
if [ -f config/payment-pro.properties ]; then
  echo "==> 已存在 config/payment-pro.properties，跳过入驻（删除后可重新入驻）"
  exit 0
fi

curl -sf --max-time 5 "$PAY_BASE/api/health" >/dev/null || { echo "!! payment-pro 未启动（$PAY_BASE），请先在 payment-pro 执行 ./run.sh"; exit 1; }
[ -f "$PAY_ROOT/config/payment-pro-cert.pem" ] || { echo "!! 缺少 $PAY_ROOT/config/payment-pro-cert.pem（payment-pro run.sh 自动生成）"; exit 1; }

echo "==> 注册/复用 ride-pro 商户..."
MEMBER=$(curl -sf "$PAY_BASE/api/members" | python3 -c '
import json,sys
ms=json.load(sys.stdin)
m=[x for x in ms if x["name"]=="ride-pro" and x["type"]=="MERCHANT"]
print(json.dumps(m[0]) if m else "")')
if [ -z "$MEMBER" ]; then
  MEMBER=$(curl -sf -X POST "$PAY_BASE/api/members" -H 'Content-Type: application/json' \
    -d '{"name":"ride-pro","type":"MERCHANT"}')
fi
[ -n "$MEMBER" ] || { echo "!! 商户注册失败"; exit 1; }

APP_ID=$(echo "$MEMBER" | python3 -c 'import json,sys;print(json.load(sys.stdin)["appId"])')
APP_SECRET=$(echo "$MEMBER" | python3 -c 'import json,sys;print(json.load(sys.stdin)["appSecret"])')
MEMBER_ID=$(echo "$MEMBER" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')

ACCOUNT_ID=$(curl -sf "$PAY_BASE/api/accounts" | python3 -c "
import json,sys
accs=json.load(sys.stdin)
a=[x for x in accs if x['memberId']=='$MEMBER_ID']
print(a[0]['id'] if a else '')")
if [ -z "$ACCOUNT_ID" ]; then
  ACCOUNT_ID=$(curl -sf -X POST "$PAY_BASE/api/accounts" -H 'Content-Type: application/json' \
    -d "{\"memberId\":\"$MEMBER_ID\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
fi

cp "$PAY_ROOT/config/payment-pro-cert.pem" config/payment-pro-cert.pem
cat > config/payment-pro.properties <<EOF
base-url=$TLS_BASE
app-id=$APP_ID
app-secret=$APP_SECRET
merchant-member-id=$MEMBER_ID
merchant-account-id=$ACCOUNT_ID
cert-path=config/payment-pro-cert.pem
callback-base-url=$CALLBACK_BASE
EOF

echo "==> 入驻完成：merchant=$MEMBER_ID account=$ACCOUNT_ID appId=$APP_ID"
echo "==> 重启 ride-pro（./run.sh）后双账本镜像生效"