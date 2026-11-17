#!/bin/bash
# 按依赖顺序停止：gateway → 业务域 → registry 最后。
# registry 必须最后停：业务进程停机时若 ZK 已失联，Dubbo shutdown hook 会阻塞在注册中心销毁上。
cd "$(dirname "$0")"
ORDER="gateway dispatch order payment push location pricing driver passenger registry"
for name in $ORDER; do
  f="run/$name.pid"
  [ -f "$f" ] || continue
  pid=$(cat "$f")
  rm -f "$f"
  kill -0 "$pid" 2>/dev/null || continue
  kill "$pid" 2>/dev/null
  echo "==> 停止 $name (pid $pid)"
  for ((i=0; i<20; i++)); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null
    echo "==> 强制结束残留进程 $name (pid $pid)"
  fi
done
echo "==> 已停止。数据保留在 data/ 目录，重新启动 run.sh 可恢复。"