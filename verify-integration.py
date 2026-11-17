#!/usr/bin/env python3
# ride-pro × payment-pro 双账本集成验证（HTTPS OpenAPI 镜像）
# 前置：两系统均已启动，且已执行 ./integrate-payment.sh 并重启 ride-pro。
# 口径：增量比对——操作前后 ride 钱包变化量 == payment-pro 账户变化量（兼容接入前历史余额）。
# 覆盖：充值/订单支付/部分退款/提现 双账本收敛 + 回调留痕 + payment-pro 宕机镜像积压恢复 + 两边对账。
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

RIDE = "http://127.0.0.1:8090"
PAY = "http://127.0.0.1:8080"
ROOT = os.path.dirname(os.path.abspath(__file__))
PAY_ROOT = os.path.abspath(os.path.join(ROOT, "..", "payment-pro"))
SUFFIX = uuid.uuid4().hex[:6]
ADMIN = {"X-Admin-Token": "ride-admin"}
PASSED = 0
FAILED = 0


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ✓ {name}")
    else:
        FAILED += 1
        print(f"  ✗ {name}  {detail}")


def req(base, method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    r = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}
    except Exception as e:
        return 0, {"error": str(e)}


def ok(base, method, path, body=None, headers=None):
    st, resp = req(base, method, path, body, headers)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def poll(fn, pred, timeout=60, interval=1):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def ride_balance(uid):
    return ok(RIDE, "GET", f"/api/wallets/{uid}")["balance"]


def pp_account_of(name):
    members = ok(PAY, "GET", "/api/members")
    m = next((x for x in members if x["name"] == name), None)
    if m is None:
        return None
    accs = ok(PAY, "GET", "/api/accounts")
    a = next((x for x in accs if x["memberId"] == m["id"]), None)
    return a["id"] if a else None


def pp_balance(uid):
    aid = pp_account_of(uid)
    if aid is None:
        return 0
    return ok(PAY, "GET", f"/api/accounting/accounts/{aid}/balance")


def merchant_account():
    props = {}
    with open(os.path.join(ROOT, "config", "payment-pro.properties")) as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                props[k] = v
    return props["merchant-account-id"]


def snap(uid):
    return ride_balance(uid), pp_balance(uid)


def converge(uid, base, expect_delta=None, timeout=90):
    """等待 ride 与 payment-pro 的余额变化量一致（镜像追平）。"""
    def done(_):
        rb, pb = snap(uid)
        dr, dp = rb - base[0], pb - base[1]
        return dr == dp and (expect_delta is None or dr == expect_delta)
    poll(lambda: None, done, timeout=timeout)
    rb, pb = snap(uid)
    return rb - base[0], pb - base[1]


def main():
    print(f"== ride-pro × payment-pro 双账本集成验证（批次 {SUFFIX}）==")
    assert req(RIDE, "GET", "/api/health")[0] == 200, "ride-pro 未启动"
    assert req(PAY, "GET", "/api/health")[0] == 200, "payment-pro 未启动"
    assert os.path.isfile(os.path.join(ROOT, "config", "payment-pro.properties")), "请先执行 ./integrate-payment.sh 并重启 ride-pro"
    merch_acc = merchant_account()
    merch0 = ok(PAY, "GET", f"/api/accounting/accounts/{merch_acc}/balance")

    ok(RIDE, "POST", "/api/admin/seed", {}, ADMIN)
    passengers = ok(RIDE, "GET", "/api/passengers")
    drivers = ok(RIDE, "GET", "/api/drivers")
    p = passengers[0]["id"]
    d = next((x["id"] for x in drivers if x.get("serviceType") == "EXPRESS"), drivers[0]["id"])

    print(f"[A] 充值镜像：乘客 {p} 充值 100000")
    base = snap(p)
    ok(RIDE, "POST", "/api/wallets/topup", {"userId": p, "amount": 100000, "bizNo": f"it-topup-{SUFFIX}"})
    dr, dp = converge(p, base, 100000)
    check("充值后双账本增量一致", dr == dp == 100000, f"rideΔ={dr} ppΔ={dp}")

    print("[B] 订单全链路：叫车→接单→到达→开始→完成→支付，三方双账本收敛")
    order = ok(RIDE, "POST", "/api/orders", {
        "passengerId": p, "originName": "起点", "originLat": 39.9087, "originLng": 116.3975,
        "destName": "终点", "destLat": 39.9300, "destLng": 116.4200,
        "requestId": f"it-call-{SUFFIX}", "serviceType": "EXPRESS"})
    oid = order["id"]
    ok(RIDE, "POST", f"/api/orders/{oid}/accept?driverId={d}")
    ok(RIDE, "POST", f"/api/orders/{oid}/arrive?driverId={d}")
    ok(RIDE, "POST", f"/api/orders/{oid}/start?driverId={d}")
    ok(RIDE, "POST", f"/api/orders/{oid}/complete?driverId={d}")
    bp, bd = snap(p), snap(d)
    plat0 = ride_balance("SYS_PLATFORM")
    ok(RIDE, "POST", f"/api/orders/{oid}/pay")
    amount = ok(RIDE, "GET", f"/api/orders/{oid}")["actualFee"]
    dr, dp = converge(p, bp, -amount)
    check("乘客扣款双账本增量一致", dr == dp == -amount, f"rideΔ={dr} ppΔ={dp}")
    dr, dp = converge(d, bd)
    check("司机入账双账本增量一致", dr == dp and dr > 0, f"rideΔ={dr} ppΔ={dp}")
    pp_order = poll(lambda: next((x for x in ok(PAY, "GET", "/api/orders")
                                  if x["subject"] == f"ride-{oid}"), None),
                    lambda o: o is not None and o["status"] in ("PAID", "CLOSED"), timeout=60)
    check("payment-pro 交易单 PAID", pp_order is not None and pp_order["status"] == "PAID", str(pp_order))
    pp_fee = ok(PAY, "GET", f"/api/payments/{pp_order['paymentId']}")["fee"] if pp_order and pp_order["paymentId"] else 0
    plat_ride = ride_balance("SYS_PLATFORM") - plat0
    plat_pp = ok(PAY, "GET", f"/api/accounting/accounts/{merch_acc}/balance") - merch0
    check("平台抽成双账本一致（pp 侧扣手续费入 SYS_FEE）", plat_ride == plat_pp + pp_fee and plat_ride > 0,
          f"ride平台Δ={plat_ride} pp商户Δ={plat_pp} ppFee={pp_fee}")

    print("[C] 部分退款镜像：退一半，司机追回+平台兜底，双账本收敛")
    bp, bd = snap(p), snap(d)
    merch1 = ok(PAY, "GET", f"/api/accounting/accounts/{merch_acc}/balance")
    plat1 = ride_balance("SYS_PLATFORM")
    ok(RIDE, "POST", f"/api/admin/orders/{oid}/refund?amount={amount // 2}", {}, ADMIN)
    dr, dp = converge(p, bp, amount // 2)
    check("退款乘客入账双账本增量一致", dr == dp == amount // 2, f"rideΔ={dr} ppΔ={dp}")
    dr, dp = converge(d, bd)
    check("退款司机追回双账本增量一致", dr == dp and dr < 0, f"rideΔ={dr} ppΔ={dp}")
    plat_ride = ride_balance("SYS_PLATFORM") - plat1
    plat_pp = ok(PAY, "GET", f"/api/accounting/accounts/{merch_acc}/balance") - merch1
    check("退款平台兜底双账本增量一致", plat_ride == plat_pp and plat_ride < 0,
          f"ride平台Δ={plat_ride} pp商户Δ={plat_pp}")

    print("[D] 提现镜像：司机提现 → 模拟渠道成功 → payment-pro 出金")
    bd = snap(d)
    # 提现额受 payment-pro 侧余额约束（接入前的历史收入未镜像，pp 余额可能小于 ride 余额）
    wd = min(1000, ride_balance(d), pp_balance(d))
    assert wd > 0, "司机无可提现余额"
    ok(RIDE, "POST", "/api/wallets/withdraw", {"userId": d, "amount": wd, "bizNo": f"it-wd-{SUFFIX}"})
    poll(lambda: ok(RIDE, "GET", f"/api/wallets/{d}/withdraws"),
         lambda ws: any(w["bizNo"] == f"it-wd-{SUFFIX}" and w["status"] in ("SUCCESS", "FAILED") for w in ws))
    dr, dp = converge(d, bd, -wd)
    check("提现后双账本增量一致", dr == dp == -wd, f"rideΔ={dr} ppΔ={dp}")

    print("[E] 回调留痕：payment-pro 异步通知送达 ride-gateway")
    time.sleep(8)
    log = open(os.path.join(ROOT, "logs", "payment.log"), encoding="utf-8", errors="ignore").read()
    check("ride 收到 payment-pro 回调留痕", "payment-pro 通知留痕" in log or "payment-pro 通知幂等命中" in log)

    print("[F] 混沌：停 payment-pro → ride 充值本地不受影响 → 重启后镜像追平")
    pp_balance_cached = pp_balance(p)
    subprocess.run([os.path.join(PAY_ROOT, "stop.sh")], cwd=PAY_ROOT, check=True, capture_output=True)
    time.sleep(2)
    base = (ride_balance(p), pp_balance_cached)
    st, _ = req(RIDE, "POST", "/api/wallets/topup", {"userId": p, "amount": 5000, "bizNo": f"it-down-{SUFFIX}"})
    check("payment-pro 宕机时 ride 充值正常", st == 200 and ride_balance(p) == base[0] + 5000, f"st={st}")
    subprocess.run([os.path.join(PAY_ROOT, "run.sh"), "--no-build"], cwd=PAY_ROOT, check=True, capture_output=True)
    poll(lambda: req(PAY, "GET", "/api/health")[0], lambda st: st == 200, timeout=180, interval=2)
    dr, dp = converge(p, base, 5000, timeout=120)
    check("恢复后镜像追平双账本增量一致", dr == dp == 5000, f"rideΔ={dr} ppΔ={dp}")

    print("[G] 两边对账：ride 账面守恒 + 镜像队列清空 + payment-pro 全量对账")
    audit = ok(RIDE, "GET", "/api/admin/audit", headers=ADMIN)
    check("ride 账面全部守恒", all(a["ok"] for a in audit), str([a for a in audit if not a["ok"]]))
    mirror = ok(RIDE, "GET", "/api/admin/mirror", headers=ADMIN)
    check("镜像队列无积压无 DEAD", mirror["pendingCount"] == 0 and mirror["deadCount"] == 0, str(mirror))
    rep = ok(PAY, "POST", "/api/recon/run")
    check("payment-pro 对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()