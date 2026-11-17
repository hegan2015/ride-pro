#!/bin/bash
# 一键编译并启动全部服务：内嵌注册中心 → 8 个业务域 → 网关（含司乘端 H5）
set -e
cd "$(dirname "$0")"

if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
  export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
echo "JAVA_HOME=$JAVA_HOME"

mkdir -p run logs data

if [ "$1" != "--no-build" ]; then
  echo "==> 编译打包（Maven Wrapper）..."
  ./mvnw package -DskipTests -q
fi

start() {
  local name=$1 module=$2
  if [ -f "run/$name.pid" ] && kill -0 "$(cat run/$name.pid)" 2>/dev/null; then
    echo "==> $name 已在运行 (pid $(cat run/$name.pid))，跳过"
    return
  fi
  nohup "$JAVA_HOME/bin/java" -jar "$module/target/$module-1.0.0.jar" > "logs/$name.log" 2>&1 &
  echo $! > "run/$name.pid"
  echo "==> 启动 $name (pid $!)"
}

wait_http() {
  local url=$1 name=$2 timeout=${3:-90}
  for ((i=0; i<timeout; i++)); do
    if curl -sf -o /dev/null --max-time 2 "$url"; then
      echo "==> $name 就绪"
      return 0
    fi
    sleep 1
  done
  echo "!! $name 在 ${timeout}s 内未就绪，查看 logs/$name.log"
  return 1
}

start registry ride-registry
wait_http "http://127.0.0.1:${ZK_HTTP_PORT:-8093}/health" registry 60

for svc in passenger driver location pricing order dispatch payment push; do
  start "$svc" "ride-$svc"
done

start gateway ride-gateway
wait_http "http://127.0.0.1:${GATEWAY_PORT:-8090}/api/health" gateway 90

echo "==> 等待全部域服务注册并通过健康检查..."
for ((i=0; i<120; i++)); do
  ready=$(curl -sf --max-time 5 "http://127.0.0.1:${GATEWAY_PORT:-8090}/api/health" \
    | python3 -c 'import sys,json; print("yes" if json.load(sys.stdin).get("allOk") else "no")' 2>/dev/null || echo "no")
  if [ "$ready" = "yes" ]; then
    echo "==> 全部 10 个进程健康 ✔"
    echo "==> 门户首页: http://127.0.0.1:${GATEWAY_PORT:-8090}/"
    echo "==> 乘客端:   http://127.0.0.1:${GATEWAY_PORT:-8090}/passenger.html"
    echo "==> 司机端:   http://127.0.0.1:${GATEWAY_PORT:-8090}/driver.html"
    exit 0
  fi
  sleep 2
done
echo "!! 仍有服务未就绪（域服务可能仍在启动，可稍后刷新 /api/health）"
exit 1