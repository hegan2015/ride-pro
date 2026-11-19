#!/usr/bin/env python3
# ride-pro × payment-pro 双账本集成验证（v1.6.0 同步回执流程）
# 前置：两系统均已启动，且已执行 ./integrate-payment.sh 并重启 ride-pro。
# 流程：ride 先落 PENDING 流水 → 同步调用 payment-pro → 回执后 CONFIRMED 动账；
#       拒绝→FAILED 退回；payment-pro 宕机→PENDING 留痕，恢复后续传确认。
# 口径：增量比对——操作前后 ride 钱包变化量 == payment-pro 账户变化量。
# 覆盖：充值/订单支付/部分退款/提现 双账本收敛 + 回执流水 + 回调留痕 + 宕机 PENDING 恢复 + 两边对账。
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
PAY_TOKEN = None
PASSED = 0
FAILED = 0


def pay_password():
    pw = os.environ.get("PP_CONSOLE_PASSWORD")
    if pw:
        return pw
    cfg = os.path.expanduser("~/.config/ride-pro/pp-console.env")
    if os.path.isfile(cfg):
        for line in open(cfg):
            if line.startswith("PP_CONSOLE_PASSWORD="):
                return line.strip().split("=", 1)[1]
    raise SystemExit("缺少 pp 控制台口令：设置 PP_CONSOLE_PASSWORD 或 ~/.config/ride-pro/pp-console.env")


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
    if base == PAY and PAY_TOKEN:
        h["Authorization"] = "Bearer " + PAY_TOKEN
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


def pp_docker_mode():
    """探测 payment-pro 当前是否 docker profile（重启时继承，避免退回 local）。"""
    try:
        pid = open(os.path.join(PAY_ROOT, "run", "gateway.pid")).read().strip()
        cmd = subprocess.run(["ps", "-p", pid, "-o", "command="], capture_output=True, text=True).stdout
        if "spring.profiles.active=docker" in cmd:
            return True
        env = subprocess.run(["ps", "eww", "-p", pid, "-o", "command="], capture_output=True, text=True).stdout
        return "SPRING_PROFILES_ACTIVE=docker" in env
    except Exception:
        return False


def main():
    global PAY_TOKEN
    print(f"== ride-pro × payment-pro 双账本集成验证（批次 {SUFFIX}）==")
    assert req(RIDE, "GET", "/api/health")[0] == 200, "ride-pro 未启动"
    assert req(PAY, "GET", "/api/health")[0] == 200, "payment-pro 未启动"
    st, login = req(PAY, "POST", "/api/auth/login",
                    {"username": os.environ.get("PP_CONSOLE_USER", "admin"), "password": pay_password()})
    assert st == 200 and login.get("token"), f"payment-pro 控制台登录失败: {login}"
    PAY_TOKEN = login["token"]
    assert os.path.isfile(os.path.join(ROOT, "config", "payment-pro.properties")), "请先执行 ./integrate-payment.sh 并重启 ride-pro"
    merch_acc = merchant_account()
    merch0 = ok(PAY, "GET", f"/api/accounting/accounts/{merch_acc}/balance")

    ok(RIDE, "POST", "/api/admin/seed", {}, ADMIN)
    passengers = ok(RIDE, "GET", "/api/passengers")
    drivers = ok(RIDE, "GET", "/api/drivers")
    p = passengers[0]["id"]
    d = next((x["id"] for x in drivers if x.get("serviceType") == "EXPRESS"), drivers[0]["id"])

    print(f"[A] 充值同步回执：乘客 {p} 充值 100000")
    base = snap(p)
    ok(RIDE, "POST", "/api/wallets/topup", {"userId": p, "amount": 100000, "bizNo": f"it-topup-{SUFFIX}"})
    dr, dp = converge(p, base, 100000)
    check("充值后双账本增量一致", dr == dp == 100000, f"rideΔ={dr} ppΔ={dp}")
    led = ok(RIDE, "GET", f"/api/admin/ledger?userId={p}&size=50", headers=ADMIN)["items"]
    entry = next((e for e in led if e["requestId"] == f"it-topup-{SUFFIX}"), None)
    check("充值流水 CONFIRMED 且带支付回执", entry is not None and entry["status"] == "CONFIRMED"
          and bool(entry.get("ppReceipt")), str(entry))
    aid = pp_account_of(p)
    fund_order = next((x for x in ok(PAY, "GET", "/api/orders")
                       if x["outTradeNo"] == f"it-topup-{SUFFIX}"), None)
    check("充值生成交易单+支付单（TOPUP/PAID）", fund_order is not None
          and fund_order["bizType"] == "TOPUP" and fund_order["status"] == "PAID"
          and bool(fund_order.get("paymentId")), str(fund_order))
    entries = ok(PAY, "GET", f"/api/accounting/accounts/{aid}/entries?limit=50")
    check("payment-pro 充值账务流水同步可见", fund_order is not None
          and any(e["refId"] == fund_order["id"] and e["dir"] == "C" for e in entries))

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

    print("[D] 提现同步出金：司机提现 → 模拟渠道 → payment-pro 回执 → SUCCESS")
    bd = snap(d)
    wd = min(1000, ride_balance(d), pp_balance(d))
    assert wd > 0, "司机无可提现余额"
    # 模拟渠道按 bizNo 哈希确定性失败，失败则换新幂等键重试直至成功
    wd_biz = None
    for i in range(6):
        wd_biz = f"it-wd-{SUFFIX}-{i}"
        ok(RIDE, "POST", "/api/wallets/withdraw", {"userId": d, "amount": wd, "bizNo": wd_biz})
        ws = poll(lambda: ok(RIDE, "GET", f"/api/wallets/{d}/withdraws"),
                  lambda ws: any(w["bizNo"] == wd_biz and w["status"] in ("SUCCESS", "FAILED") for w in ws))
        st = next(w["status"] for w in ws if w["bizNo"] == wd_biz)
        if st == "SUCCESS":
            break
    check("提现终态 SUCCESS（支付系统回执确认）", st == "SUCCESS", f"status={st}")
    dr, dp = converge(d, bd, -wd)
    check("提现后双账本增量一致", dr == dp == -wd, f"rideΔ={dr} ppΔ={dp}")
    aid = pp_account_of(d)
    fund_order = next((x for x in ok(PAY, "GET", "/api/orders")
                       if x["outTradeNo"] == wd_biz), None)
    check("提现生成交易单+支付单（WITHDRAW/PAID）", fund_order is not None
          and fund_order["bizType"] == "WITHDRAW" and fund_order["status"] == "PAID"
          and bool(fund_order.get("paymentId")), str(fund_order))
    entries = ok(PAY, "GET", f"/api/accounting/accounts/{aid}/entries?limit=50")
    check("payment-pro 提现出金流水可见", fund_order is not None
          and any(e["refId"] == fund_order["id"] and e["dir"] == "D" for e in entries))

    print("[E] 回调留痕：payment-pro 异步通知送达 ride-gateway")
    time.sleep(8)
    log = open(os.path.join(ROOT, "logs", "payment.log"), encoding="utf-8", errors="ignore").read()
    check("ride 收到 payment-pro 回调留痕", "payment-pro 通知留痕" in log or "payment-pro 通知幂等命中" in log)

    print("[F] 混沌：停 payment-pro → ride 充值落 PENDING 不动账 → 重启后续传回执确认")
    pp_was_docker = pp_docker_mode()
    pp_balance_cached = pp_balance(p)
    subprocess.run([os.path.join(PAY_ROOT, "stop.sh")], cwd=PAY_ROOT, check=True, capture_output=True)
    time.sleep(2)
    base = (ride_balance(p), pp_balance_cached)
    st, resp = req(RIDE, "POST", "/api/wallets/topup", {"userId": p, "amount": 5000, "bizNo": f"it-down-{SUFFIX}"})
    check("payment-pro 宕机时充值返回处理中（PENDING）", st != 200 and "PENDING" in json.dumps(resp, ensure_ascii=False),
          f"st={st} resp={resp}")
    check("宕机期间钱包不动账（回执未确认）", ride_balance(p) == base[0], f"balance={ride_balance(p)} base={base[0]}")
    led = ok(RIDE, "GET", f"/api/admin/ledger?userId={p}&size=50", headers=ADMIN)["items"]
    check("宕机期间流水 PENDING 留痕", any(e["requestId"] == f"it-down-{SUFFIX}" and e["status"] == "PENDING" for e in led))
    run_cmd = [os.path.join(PAY_ROOT, "run.sh"), "--no-build"]
    if pp_was_docker:
        run_cmd.insert(1, "--docker")
    subprocess.run(run_cmd, cwd=PAY_ROOT, check=True, capture_output=True)
    poll(lambda: req(PAY, "GET", "/api/health")[0], lambda st: st == 200, timeout=180, interval=2)
    dr, dp = converge(p, base, 5000, timeout=120)
    check("恢复后续传确认双账本增量一致", dr == dp == 5000, f"rideΔ={dr} ppΔ={dp}")
    led = ok(RIDE, "GET", f"/api/admin/ledger?userId={p}&size=50", headers=ADMIN)["items"]
    check("恢复后流水转 CONFIRMED", any(e["requestId"] == f"it-down-{SUFFIX}" and e["status"] == "CONFIRMED" for e in led))

    print("[G] 两边对账：ride 账面守恒 + 镜像队列清空 + payment-pro 全量对账")
    audit = ok(RIDE, "GET", "/api/admin/audit", headers=ADMIN)
    check("ride 账面全部守恒", all(a["ok"] for a in audit), str([a for a in audit if not a["ok"]]))
    mirror = ok(RIDE, "GET", "/api/admin/mirror", headers=ADMIN)
    check("镜像队列无积压无 DEAD", mirror["pendingCount"] == 0 and mirror["deadCount"] == 0, str(mirror))
    rep = poll(lambda: ok(PAY, "POST", "/api/recon/run"), lambda r: r["pass"], timeout=60, interval=2)
    check("payment-pro 对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()