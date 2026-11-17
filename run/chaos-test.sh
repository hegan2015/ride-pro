#!/bin/bash
# ride-pro 混沌测试（REST 级，不删任何数据）：
#   并发抢单唯一胜者 / 并发支付只扣一次 / 取消费 / 司机取消回派单 /
#   管理端退款+账面守恒 / 提现冻结状态机 / 部分退款+退款守卫 / 风控限额 /
#   幂等键必填 / 免密自动扣款 / 管理鉴权 / 杀 order 验证支付+退款 outbox 与对账兜底
# 用法: ./run/chaos-test.sh   （需先 ./run.sh 启动全部服务）
set -u
cd "$(dirname "$0")/.."

BASE="http://127.0.0.1:${GATEWAY_PORT:-8090}"
ADMIN_TOKEN="${ADMIN_TOKEN:-ride-admin}"
SUFFIX="$(date +%s)$$"
PASS=0; FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✔ $*"; }
bad()  { FAIL=$((FAIL+1)); say "  ✘ $*"; }
check(){ if [ "$1" = "$2" ]; then ok "$3 ($1)"; else bad "$3 期望[$2] 实际[$1]"; fi; }

# req METHOD PATH [BODY] [EXTRA_HEADER] → 设置 HTTP_CODE / HTTP_BODY
req() {
  local method=$1 path=$2 body=${3:-} hdr=${4:-}
  local args=(-s -o /tmp/chaos-body.$$ -w '%{http_code}' -X "$method" "$BASE$path" --max-time 15)
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$hdr" ] && args+=(-H "$hdr")
  HTTP_CODE=$(curl "${args[@]}")
  HTTP_BODY=$(cat /tmp/chaos-body.$$)
}
jget() { python3 -c "import sys,json;d=json.loads(sys.stdin.read());print($1)" <<<"$HTTP_BODY" 2>/dev/null; }

say "== 0. 前置：健康检查 =="
req GET /api/health
check "$(jget "d['allOk']")" "True" "全部服务健康"
[ "$HTTP_CODE" != "200" ] && { say "服务未就绪，先执行 ./run.sh"; exit 1; }

say "== 1. 造数：乘客 + 双司机（只新增，不动存量数据） =="
req POST /api/passengers "{\"name\":\"混沌乘客$SUFFIX\",\"phone\":\"139${SUFFIX: -8}\"}"
P=$(jget "d['id']")
req POST /api/wallets/topup "{\"userId\":\"$P\",\"amount\":100000,\"bizNo\":\"CHAOS-TOPUP-$SUFFIX\"}"
check "$(jget "d['balance']")" "100000" "乘客充值 1000 元"

mk_driver() { # name carNo lat lng idx → 设置 DID
  req POST /api/drivers "{\"name\":\"$1\",\"phone\":\"138${SUFFIX: -7}$5\",\"carNo\":\"$2\",\"carModel\":\"测试车\",\"serviceType\":\"EXPRESS\"}"
  DID=$(jget "d['id']")
  req POST /api/locations "{\"driverId\":\"$DID\",\"lat\":$3,\"lng\":$4}"
  req POST "/api/drivers/$DID/online?online=true"
}
mk_driver "混沌司机A$SUFFIX" "京C·A${SUFFIX: -5}" 39.9090 116.3975 1
DA=$DID
mk_driver "混沌司机B$SUFFIX" "京C·B${SUFFIX: -5}" 39.9095 116.3980 2
DB=$DID

call_order() { # → 设置 OID
  req POST /api/orders "{\"passengerId\":\"$P\",\"originName\":\"混沌起点\",\"originLat\":39.9087,\"originLng\":116.3975,\"destName\":\"混沌终点\",\"destLat\":39.9400,\"destLng\":116.4300,\"serviceType\":\"EXPRESS\",\"requestId\":\"CHAOS-CALL-$SUFFIX-$1\"}"
  OID=$(jget "d['id']")
}

say "== 2. 并发抢单：恰好一个胜者，败者司机状态回滚 =="
call_order RACE
for i in 1 2 3 4; do
  curl -s -o /tmp/chaos-accept-$i.$$ -w '%{http_code}\n' -X POST "$BASE/api/orders/$OID/accept?driverId=$DA" --max-time 15 >> /tmp/chaos-codes-a.$$ &
  curl -s -o /tmp/chaos-accept-$((i+4)).$$ -w '%{http_code}\n' -X POST "$BASE/api/orders/$OID/accept?driverId=$DB" --max-time 15 >> /tmp/chaos-codes-a.$$ &
done
wait
WINS=$(grep -c '^200$' /tmp/chaos-codes-a.$$ || true)
check "$WINS" "1" "8 路并发抢单成功数"
req GET "/api/orders/$OID"
WINNER=$(jget "d['driverId']")
check "$(jget "d['status']")" "ACCEPTED" "订单状态"
LOSER=$([ "$WINNER" = "$DA" ] && echo "$DB" || echo "$DA")
req GET "/api/drivers/$LOSER"
check "$(jget "d['status']")" "ONLINE" "败者司机回滚 ONLINE"

say "== 3. 行程推进 + 轨迹计价 =="
req POST "/api/orders/$OID/arrive?driverId=$WINNER"
req POST /api/locations "{\"driverId\":\"$WINNER\",\"lat\":39.9087,\"lng\":116.3975,\"orderId\":\"$OID\",\"passengerId\":\"$P\"}"
req POST "/api/orders/$OID/start?driverId=$WINNER"
req POST /api/locations "{\"driverId\":\"$WINNER\",\"lat\":39.9200,\"lng\":116.4100,\"orderId\":\"$OID\",\"passengerId\":\"$P\"}"
req POST /api/locations "{\"driverId\":\"$WINNER\",\"lat\":39.9400,\"lng\":116.4300,\"orderId\":\"$OID\",\"passengerId\":\"$P\"}"
req POST "/api/orders/$OID/complete?driverId=$WINNER"
check "$(jget "d['status']")" "COMPLETED" "完单状态"
FEE=$(jget "d['actualFee']")
[ "${FEE:-0}" -gt 0 ] && ok "轨迹计价 actualFee=$FEE 分" || bad "actualFee 非法: $FEE"
req GET "/api/locations/track/$OID"
N=$(jget "len(d)")
[ "${N:-0}" -ge 2 ] && ok "轨迹点数 $N" || bad "轨迹点数不足: $N"

say "== 4. 并发支付（双击/重试风暴）：只扣一次 =="
BAL_BEFORE=$(req GET "/api/wallets/$P"; jget "d['balance']")
: > /tmp/chaos-codes-p.$$
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/orders/$OID/pay" --max-time 15 >> /tmp/chaos-codes-p.$$ &
done
wait
PAY_OK=$(grep -c '^200$' /tmp/chaos-codes-p.$$ || true)
[ "${PAY_OK:-0}" -ge 1 ] && ok "支付成功响应数 $PAY_OK（其余幂等/冲突）" || bad "无支付成功响应"
req GET "/api/wallets/$P/ledger?orderId=$OID&bizType=PAY_DEBIT&size=50"
check "$(jget "d['total']")" "1" "PAY_DEBIT 流水条数"
req GET "/api/wallets/$P"
check "$(jget "d['balance']")" "$((BAL_BEFORE - FEE))" "乘客余额只扣一次"
req GET "/api/orders/$OID"
check "$(jget "d['status']")" "PAID" "订单 PAID（outbox/对账兜底）"

say "== 5. 取消费：ARRIVED 后乘客取消扣 3 元 =="
call_order CANCEL
req POST "/api/orders/$OID/accept?driverId=$DA"
req POST "/api/orders/$OID/arrive?driverId=$DA"
req POST "/api/orders/$OID/cancel?reason=chaos-cancel"
check "$(jget "d['status']")" "CANCELLED" "取消状态"
check "$(jget "d['cancelFee']")" "300" "取消费 300 分"
req GET "/api/wallets/$P/ledger?orderId=$OID&bizType=CANCEL_FEE_DEBIT&size=10"
check "$(jget "d['total']")" "1" "取消费扣款流水"
req GET "/api/wallets/$DA/ledger?orderId=$OID&bizType=CANCEL_FEE_CREDIT&size=10"
check "$(jget "d['total']")" "1" "司机取消费入账流水"

say "== 6. 司机取消：回派单 + 司机释放 =="
call_order DCANCEL
req POST "/api/orders/$OID/accept?driverId=$DB"
req POST "/api/orders/$OID/driver-cancel?driverId=$DB&reason=chaos-driver-cancel"
req GET "/api/orders/$OID"
check "$(jget "d['status']")" "DISPATCHING" "司机取消后回派单"
req GET "/api/drivers/$DB"
check "$(jget "d['status']")" "ONLINE" "司机释放 ONLINE"
req POST "/api/orders/$OID/cancel?reason=chaos-giveup"
check "$(jget "d['cancelFee']")" "0" "派单中取消免费"

say "== 7. 管理端：鉴权 / 退款 / 账面守恒 =="
req GET /api/admin/overview
check "$HTTP_CODE" "401" "无 token 拒绝"
req GET /api/admin/overview "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "200" "携带 token 放行"
PAID_OID=$(req GET "/api/orders/query?passengerId=$P&status=PAID&size=1"; jget "d['items'][0]['id']")
req GET "/api/wallets/$P"
BAL_BEFORE_REFUND=$(jget "d['balance']")
req POST "/api/admin/orders/$PAID_OID/refund?reason=chaos-refund" "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "200" "退款接口"
req GET "/api/orders/$PAID_OID"
check "$(jget "d['status']")" "REFUNDED" "订单 REFUNDED"
req GET "/api/wallets/$P"
check "$(jget "d['balance']")" "$((BAL_BEFORE_REFUND + FEE))" "乘客全额到账"
req GET /api/admin/audit "" "X-Admin-Token: $ADMIN_TOKEN"
check "$(jget "all(a['ok'] for a in d)")" "True" "全部账户账面守恒"

say "== 8. 提现状态机：受理冻结 → 渠道结算 → 幂等 =="
req GET "/api/wallets/$DA"
DBAL=$(jget "d['balance']")
req POST /api/wallets/withdraw "{\"userId\":\"$DA\",\"amount\":$((DBAL + 1)),\"bizNo\":\"CHAOS-WD-OVER-$SUFFIX\"}"
check "$HTTP_CODE" "409" "超可用余额提现拒绝"
req POST /api/wallets/withdraw "{\"userId\":\"$DA\",\"amount\":$DBAL,\"bizNo\":\"CHAOS-WD-$SUFFIX\"}"
check "$HTTP_CODE" "200" "提现受理"
check "$(jget "d['frozenAmount']")" "$DBAL" "受理后金额冻结"
check "$(jget "d['availableBalance']")" "0" "受理后可用余额为 0"
WD_ST=""
for ((i=0; i<20; i++)); do
  sleep 1
  req GET "/api/wallets/$DA/withdraws"
  WD_ST=$(jget "d[0]['status']")
  [ "$WD_ST" = "SUCCESS" ] || [ "$WD_ST" = "FAILED" ] && break
done
check "$WD_ST" "SUCCESS" "渠道结算成功（默认失败率 0）"
req GET "/api/wallets/$DA"
check "$(jget "d['balance']")" "0" "结算后余额扣减"
check "$(jget "d['frozenAmount']")" "0" "结算后解冻"
req POST /api/wallets/withdraw "{\"userId\":\"$DA\",\"amount\":$DBAL,\"bizNo\":\"CHAOS-WD-$SUFFIX\"}"
check "$(jget "d['balance']")" "0" "提现幂等回放不重复扣"
req GET "/api/wallets/$DA/ledger?bizType=WITHDRAW&size=50"
check "$(jget "d['total']")" "1" "WITHDRAW 流水恰好一条"

say "== 9. 部分退款 / 退款守卫 / 风控限额 / 幂等键必填 =="
call_order PARTIAL
req POST "/api/orders/$OID/accept?driverId=$DB"
req POST "/api/orders/$OID/arrive?driverId=$DB"
req POST "/api/orders/$OID/start?driverId=$DB"
req POST "/api/orders/$OID/complete?driverId=$DB"
FEE2=$(jget "d['actualFee']")
req POST "/api/orders/$OID/pay"
check "$(jget "d['status']")" "PAID" "部分退款前置订单 PAID"
HALF=$((FEE2 / 2))
req POST "/api/admin/orders/$OID/refund?reason=chaos-partial&amount=$HALF" "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "200" "部分退款接口"
req GET "/api/orders/$OID"
check "$(jget "d['status']")" "PAID" "部分退款后保持 PAID"
check "$(jget "d['refundedAmount']")" "$HALF" "累计已退=本次金额"
check "$(jget "d['refundCount']")" "1" "退款笔数 1"
req POST "/api/admin/orders/$OID/refund?reason=chaos-over&amount=$((FEE2 - HALF + 1))" "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "400" "超剩余可退拒绝"
req POST "/api/admin/orders/$OID/refund?reason=chaos-rest&amount=$((FEE2 - HALF))" "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "200" "补齐剩余退款"
req GET "/api/orders/$OID"
check "$(jget "d['status']")" "REFUNDED" "退满后 REFUNDED"
check "$(jget "d['refundedAmount']")" "$FEE2" "累计已退=实付"
req GET "/api/wallets/$P/ledger?orderId=$OID&bizType=REFUND_CREDIT&size=10"
check "$(jget "d['total']")" "2" "退款到账流水两笔"
call_order NOPAY
req POST "/api/admin/orders/$OID/refund?reason=chaos-nopay" "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "409" "未支付订单退款拒绝"
req POST "/api/wallets/topup" "{\"userId\":\"$P\",\"amount\":1000001,\"bizNo\":\"CHAOS-TOPUP-OVER-$SUFFIX\"}"
check "$HTTP_CODE" "409" "充值单笔限额拦截"
req POST "/api/wallets/topup" "{\"userId\":\"$P\",\"amount\":100}"
check "$HTTP_CODE" "400" "缺幂等键拒绝"
req GET /api/admin/outbox/dead "" "X-Admin-Token: $ADMIN_TOKEN"
check "$HTTP_CODE" "200" "DEAD 列表接口"
req GET /api/admin/audit "" "X-Admin-Token: $ADMIN_TOKEN"
check "$(jget "all(a['ok'] for a in d)")" "True" "部分退款后账面守恒"

say "== 10. 免密自动扣款：完单后服务端自动支付 =="
req POST "/api/passengers/$P/auto-pay?enabled=true"
check "$(jget "d['autoPay']")" "True" "开启免密授权"
call_order AUTOPAY
req POST "/api/orders/$OID/accept?driverId=$DA"
req POST "/api/orders/$OID/arrive?driverId=$DA"
req POST "/api/orders/$OID/start?driverId=$DA"
req POST "/api/orders/$OID/complete?driverId=$DA"
AP_OID=$OID
AP_ST=""
for ((i=0; i<75; i++)); do
  sleep 1
  req GET "/api/orders/$AP_OID"
  AP_ST=$(jget "d['status']")
  [ "$AP_ST" = "PAID" ] && break
done
check "$AP_ST" "PAID" "宽限期后服务端自动扣款（无需客户端支付）"
req POST "/api/passengers/$P/auto-pay?enabled=false"
check "$(jget "d['autoPay']")" "False" "关闭免密授权"

say "== 11. 进程级混沌：杀 order → outbox 滞留 → 重启对账兜底 =="
if [ -f run/order.pid ] && kill -0 "$(cat run/order.pid)" 2>/dev/null; then
  call_order KILL
  req POST "/api/orders/$OID/accept?driverId=$DB"
  req POST "/api/orders/$OID/arrive?driverId=$DB"
  req POST "/api/orders/$OID/start?driverId=$DB"
  req POST "/api/orders/$OID/complete?driverId=$DB"
  KILL_FEE=$(jget "d['actualFee']")
  # 支付请求发出的同时杀掉 order 进程，制造「钱已划转/状态未推进」窗口
  curl -s -o /dev/null -X POST "$BASE/api/orders/$OID/pay" --max-time 15 &
  PAY_PID=$!
  OLD_PID=$(cat run/order.pid)
  kill "$OLD_PID" 2>/dev/null
  wait $PAY_PID 2>/dev/null
  for ((i=0; i<30; i++)); do kill -0 "$OLD_PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$OLD_PID" 2>/dev/null && { bad "旧 order 进程 30s 未退出"; exit 1; }
  say "  order 进程已终止，等待 outbox 重试窗口..."
  sleep 6
  req GET /api/admin/outbox "" "X-Admin-Token: $ADMIN_TOKEN"
  say "  outbox: pending=$(jget "d['pendingCount']") sent=$(jget "d['sentCount']") maxRetries=$(jget "d['maxRetries']")"
  if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
    export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
  nohup "$JAVA_HOME/bin/java" -jar ride-order/target/ride-order-1.0.0.jar >> logs/order.log 2>&1 &
  echo $! > run/order.pid
  say "  order 已重启 (pid $(cat run/order.pid))，等待注册与对账..."
  for ((i=0; i<60; i++)); do
    req GET /api/health
    [ "$(jget "d['allOk']")" = "True" ] && break
    sleep 2
  done
  sleep 8
  req GET "/api/orders/$OID"
  ST=$(jget "d['status']")
  if [ "$ST" = "COMPLETED" ]; then
    # 支付请求在进程死亡时未到达支付域：重新支付，幂等键保证不重复扣
    req POST "/api/orders/$OID/pay"
    sleep 3
    req GET "/api/orders/$OID"
    ST=$(jget "d['status']")
  fi
  check "$ST" "PAID" "崩溃窗口后订单最终 PAID"
  req GET "/api/wallets/$P/ledger?orderId=$OID&bizType=PAY_DEBIT&size=10"
  check "$(jget "d['total']")" "1" "崩溃窗口支付仍只扣一次"
  req GET /api/admin/outbox "" "X-Admin-Token: $ADMIN_TOKEN"
  check "$(jget "d['pendingCount']")" "0" "outbox 全部投递完成"
  req GET /api/admin/audit "" "X-Admin-Token: $ADMIN_TOKEN"
  check "$(jget "all(a['ok'] for a in d)")" "True" "混沌后账面仍守恒"
else
  say "  跳过（run/order.pid 不存在，请用 ./run.sh 启动后重试）"
fi

say "== 12. 进程级混沌：退款窗口杀 order → refund outbox/对账兜底 =="
if [ -f run/order.pid ] && kill -0 "$(cat run/order.pid)" 2>/dev/null; then
  call_order RKILL
  req POST "/api/orders/$OID/accept?driverId=$DB"
  req POST "/api/orders/$OID/arrive?driverId=$DB"
  req POST "/api/orders/$OID/start?driverId=$DB"
  req POST "/api/orders/$OID/complete?driverId=$DB"
  req POST "/api/orders/$OID/pay"
  RK_OID=$OID
  # 退款请求发出的同时杀 order：可能落在「支付域已退款/订单未同步」窗口
  curl -s -o /dev/null -X POST "$BASE/api/admin/orders/$RK_OID/refund?reason=chaos-refund-kill" -H "X-Admin-Token: $ADMIN_TOKEN" --max-time 15 &
  REF_PID=$!
  OLD_PID=$(cat run/order.pid)
  kill "$OLD_PID" 2>/dev/null
  wait $REF_PID 2>/dev/null
  for ((i=0; i<30; i++)); do kill -0 "$OLD_PID" 2>/dev/null || break; sleep 1; done
  kill -0 "$OLD_PID" 2>/dev/null && { bad "旧 order 进程 30s 未退出"; exit 1; }
  say "  order 进程已终止，refund outbox 重试窗口..."
  sleep 6
  if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
    export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
  nohup "$JAVA_HOME/bin/java" -jar ride-order/target/ride-order-1.0.0.jar >> logs/order.log 2>&1 &
  echo $! > run/order.pid
  say "  order 已重启 (pid $(cat run/order.pid))，等待注册与投递..."
  for ((i=0; i<60; i++)); do
    req GET /api/health
    [ "$(jget "d['allOk']")" = "True" ] && break
    sleep 2
  done
  RK_ST=""
  for ((i=0; i<60; i++)); do
    sleep 1
    req GET "/api/orders/$RK_OID"
    RK_ST=$(jget "d['status']")
    [ "$RK_ST" = "REFUNDED" ] && break
    # 退款请求未到达支付域（进程先死）：重发退款，序号幂等键保证不重复退
    if [ "$RK_ST" = "PAID" ] && [ "$(jget "d['refundedAmount']")" = "0" ] && [ "$i" = "10" ]; then
      req POST "/api/admin/orders/$RK_OID/refund?reason=chaos-refund-retry" "" "X-Admin-Token: $ADMIN_TOKEN"
    fi
  done
  check "$RK_ST" "REFUNDED" "退款崩溃窗口后订单最终 REFUNDED"
  req GET "/api/wallets/$P/ledger?orderId=$RK_OID&bizType=REFUND_CREDIT&size=10"
  check "$(jget "d['total']")" "1" "崩溃窗口退款只退一次"
  req GET /api/admin/outbox "" "X-Admin-Token: $ADMIN_TOKEN"
  check "$(jget "d['pendingCount']")" "0" "支付+退款 outbox 全部投递完成"
  req GET /api/admin/audit "" "X-Admin-Token: $ADMIN_TOKEN"
  check "$(jget "all(a['ok'] for a in d)")" "True" "退款混沌后账面仍守恒"
else
  say "  跳过（run/order.pid 不存在，请用 ./run.sh 启动后重试）"
fi

rm -f /tmp/chaos-body.$$ /tmp/chaos-codes-a.$$ /tmp/chaos-codes-p.$$ /tmp/chaos-accept-*.$$
say ""
say "== 结果: $PASS 通过, $FAIL 失败 =="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1