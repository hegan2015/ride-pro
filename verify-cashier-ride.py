#!/usr/bin/env python3
# ride-pro 收银台全链路自证（ride 网关 → payment-pro cashier.* 9 方法）
# 前置：ride 与 payment-pro 均已启动且已 ./integrate-payment.sh；本脚本不停/启任何服务。
# 数据：独立前缀 RDCSH + uuid，只新增不删改既有行。
# 覆盖：methods/code.create/code.parse/code.query/scanPay/verify/payVerified/payPassword.set/sms.send
#       + 终态 SUCCESS + 双账本余额 + 幂等不重复扣款 + 码过期 409 + 卡号脱敏 + 短信掩码 + 四型验证。
import json
import os
import time
import urllib.error
import urllib.request
import uuid

RIDE = "http://127.0.0.1:8090"
PAY = "http://127.0.0.1:8080"
ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:8]
TAG = "RDCSH" + SUFFIX
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
        with urllib.request.urlopen(r, timeout=40) as resp:
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
    """200 硬断言；仅对瞬时通信故障（503 C0001 / 网络 0）同参重试——
    所有写入口均确定性幂等（订单 requestId / PAY-{orderId} / bizNo），重试不产生重复资金。"""
    for attempt in range(5):
        st, resp = req(base, method, path, body, headers)
        if st == 200:
            return resp
        if st in (0, 503) and attempt < 4:
            time.sleep(6)
            continue
        break
    raise AssertionError(f"{method} {path} 期望 200，实际 {st}: {resp}")


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
    m = next((x for x in ok(PAY, "GET", "/api/members") if x["name"] == name), None)
    if m is None:
        return None
    a = next((x for x in ok(PAY, "GET", "/api/accounts") if x["memberId"] == m["id"]), None)
    return a["id"] if a else None


def pp_balance(uid):
    aid = pp_account_of(uid)
    return ok(PAY, "GET", f"/api/accounting/accounts/{aid}/balance") if aid else 0


def new_trip(p, d, label):
    """真实行程订单：叫车→接单→到达→开始→完成（COMPLETED 待支付）。
    上一笔支付后司机由 pay_outbox 异步释放，接单遇 BUSY 时等待重试（不放宽任何断言）。"""
    o = ok(RIDE, "POST", "/api/orders", {
        "passengerId": p, "originName": f"{TAG}-{label}-起点", "originLat": 39.9087, "originLng": 116.3975,
        "destName": f"{TAG}-{label}-终点", "destLat": 39.9300, "destLng": 116.4200,
        "requestId": f"{TAG}-call-{label}-{uuid.uuid4().hex[:6]}", "serviceType": "EXPRESS"})
    oid = o["id"]
    for attempt in range(30):
        st, r = req(RIDE, "POST", f"/api/orders/{oid}/accept?driverId={d}")
        if st == 200:
            break
        if st in (0, 503) or (st == 409 and "BUSY" in json.dumps(r, ensure_ascii=False)):
            time.sleep(3)
            continue
        raise AssertionError(f"接单失败 {oid}: {st} {r}")
    else:
        raise AssertionError(f"司机持续 BUSY，无法接单 {oid}")
    for step in ("arrive", "start", "complete"):
        ok(RIDE, "POST", f"/api/orders/{oid}/{step}?driverId={d}")
    return ok(RIDE, "GET", f"/api/orders/{oid}")


def cashier_verify(p, oid, vtype, **kw):
    body = {"userId": p, "orderId": oid, "verifyType": vtype}
    body.update(kw)
    return ok(RIDE, "POST", "/api/cashier/verify", body)


def warmup():
    """域间边预热：重启后首调用可能撞上 Dubbo invoker 重连窗口。
    除只读接口外，真实打一次收银台渲染（ride-gateway→ride-payment→pp member/account/trade 全链路），
    直到成功且延迟收敛，避免把冷启动超时误判为功能缺陷。"""
    for _ in range(3):
        req(RIDE, "GET", "/api/health")
        req(RIDE, "GET", "/api/passengers")
        req(RIDE, "GET", "/api/drivers")
        req(RIDE, "GET", "/api/orders?passengerId=" + (os.environ.get("RDCSH_PASSENGER") or "P1"))
        req(PAY, "GET", "/api/members")
        req(PAY, "GET", "/api/accounts")
        time.sleep(3)
    probe_order = os.environ.get("RDCSH_WARM_ORDER")
    probe_user = os.environ.get("RDCSH_PASSENGER")
    if probe_order and probe_user:
        for i in range(12):
            t0 = time.time()
            st, _ = req(RIDE, "GET", f"/api/cashier/methods?userId={probe_user}&orderId={probe_order}")
            cost = time.time() - t0
            print(f"  … 收银台预热 {i+1}: st={st} {cost:.1f}s")
            if st == 200 and cost < 4:
                return
            time.sleep(5)


def fund(uid, amount, biz_no, target):
    """充值（bizNo 幂等）：通信异常/处理中一律同键重试，绝不换新键，避免重复入账。"""
    for _ in range(8):
        st, r = req(RIDE, "POST", "/api/wallets/topup", {"userId": uid, "amount": amount, "bizNo": biz_no})
        if st == 200:
            return True
        bal = poll(lambda: ride_balance(uid), lambda b: b >= target, timeout=45, interval=3)
        if bal >= target:
            return True
        print(f"  … 充值待确认（st={st}），同键重试 {biz_no}")
    return ride_balance(uid) >= target


def main():
    global PAY_TOKEN
    print(f"== ride 收银台全链路自证（批次 {TAG}）==")
    assert req(RIDE, "GET", "/api/health")[1].get("allOk"), "ride 未全就绪"
    assert req(PAY, "GET", "/api/health")[0] == 200, "payment-pro 未启动"
    st, login = req(PAY, "POST", "/api/auth/login",
                    {"username": os.environ.get("PP_CONSOLE_USER", "admin"), "password": pay_password()})
    assert st == 200 and login.get("token"), f"pp 控制台登录失败: {login}"
    PAY_TOKEN = login["token"]
    warmup()

    drivers = ok(RIDE, "GET", "/api/drivers")
    reuse_d = os.environ.get("RDCSH_DRIVER")
    d = reuse_d or ok(RIDE, "POST", "/api/drivers", {
        "name": f"{TAG}-司机", "phone": "13800000000", "carNo": f"京A{SUFFIX[:5].upper()}",
        "carModel": "收银台验证车", "serviceType": "EXPRESS"})["id"]
    ok(RIDE, "POST", f"/api/drivers/{d}/online?online=true")
    reuse = os.environ.get("RDCSH_PASSENGER")
    p = reuse or ok(RIDE, "POST", "/api/passengers", {"name": f"{TAG}-乘客", "phone": "13900000000"})["id"]
    print(f"乘客 {p} / 司机 {d}")

    print("[0] 充值建底 + 设置支付口令（cashier.payPassword.set）")
    assert fund(p, 200000, f"{TAG}-topup", 200000), "充值未到账，无法继续"
    check("充值到账（双账本镜像）", ride_balance(p) >= 200000, f"balance={ride_balance(p)}")
    pwd = "246813"
    st, r = req(RIDE, "POST", "/api/cashier/pay-password", {"userId": p, "password": pwd})
    if st == 403:  # 已设过口令：带旧口令重置（同值），验证重置链路
        st, r = req(RIDE, "POST", "/api/cashier/pay-password", {"userId": p, "password": pwd, "oldPassword": pwd})
    check("cashier.payPassword.set 返回裸 true", st == 200 and r is True, f"st={st} r={r}")
    st, r = req(RIDE, "POST", "/api/cashier/pay-password", {"userId": p, "password": "12345"})
    check("非 6 位口令被拒 A0400", st == 400 and r.get("code") == "A0400", f"st={st} r={r}")

    print("[A] cashier.methods 渲染 + 超免密未验证拦截")
    o1 = new_trip(p, d, "A")
    oid1, amt1 = o1["id"], o1["actualFee"]
    st, m = req(RIDE, "GET", f"/api/cashier/methods?userId={p}&orderId={oid1}")
    check("cashier.methods 200 且返回 9 种方式", st == 200 and len(m.get("methods", [])) == 9,
          f"st={st} n={len(m.get('methods', []))}")
    check("实付金额与订单一致（分）", m.get("actualAmount") == amt1, f"pp={m.get('actualAmount')} ride={amt1}")
    free = m.get("freeVerifyLimit")
    check("返回小额免密限额 freeVerifyLimit", isinstance(free, int) and free > 0, str(free))
    bal_m = next((x for x in m["methods"] if x["method"] == "BALANCE"), None)
    check("BALANCE 可用且带账户余额", bal_m and bal_m["available"] and bal_m["accounts"][0]["balance"] > 0, str(bal_m))
    reserved = [x for x in m["methods"] if x.get("category") == "RESERVED"]
    check("预留方式标不可用并带原因", len(reserved) == 5 and all(not x["available"] and x.get("unavailableReason") for x in reserved),
          str([(x["method"], x.get("unavailableReason")) for x in reserved]))
    check("methods 响应不含任何密钥字段", "appSecret" not in json.dumps(m) and "certPublicKey" not in json.dumps(m))
    st, r = req(RIDE, "POST", "/api/cashier/pay", {"userId": p, "orderId": oid1, "method": "BALANCE"})
    check("超免密限额未验证被拒 A0403", amt1 <= free or (st == 403 and r.get("code") == "A0403"), f"amt={amt1} free={free} st={st} r={r}")

    print("[B] cashier.verify 四型 + 错口令原因/剩余次数")
    bad = cashier_verify(p, oid1, "PASSWORD", payPassword="000000")
    check("错口令 pass=false 且给剩余次数", bad.get("pass") is False and bad.get("remainAttempts") is not None, str(bad))
    check("验证响应不回显口令", "000000" not in json.dumps(bad) and "payPassword" not in json.dumps(bad))
    v_sms_send = ok(RIDE, "POST", "/api/cashier/sms", {"userId": p, "orderId": oid1})
    check("cashier.sms.send 默认掩码 ******", v_sms_send.get("smsCode") == "******" and v_sms_send.get("smsCodeMasked") is True,
          str(v_sms_send))
    sbx = ok(RIDE, "POST", "/api/cashier/sms", {"userId": p, "orderId": oid1, "sandbox": True})
    check("sandbox=true 返 6 位明文沙箱码", sbx.get("sandbox") is True and str(sbx.get("smsCode", "")).isdigit()
          and len(str(sbx["smsCode"])) == 6, str(sbx))
    v_sms = cashier_verify(p, oid1, "SMS", smsCode=str(sbx["smsCode"]))
    check("SMS 验证通过并签发令牌", v_sms.get("pass") is True and bool(v_sms.get("verifyToken")), str(v_sms))
    v_bio = cashier_verify(p, oid1, "BIO", bioToken=f"BIO-{TAG}-simulate")
    check("BIO（指纹模拟）验证通过", v_bio.get("pass") is True and bool(v_bio.get("verifyToken")), str(v_bio))
    v_free = cashier_verify(p, oid1, "SMALL_AMOUNT_FREE")
    check("SMALL_AMOUNT_FREE 超限额不放行", v_free.get("pass") is False or amt1 <= free, str(v_free))
    v_pwd = cashier_verify(p, oid1, "PASSWORD", payPassword=pwd)
    check("PASSWORD 验证通过并签发令牌", v_pwd.get("pass") is True and bool(v_pwd.get("verifyToken")), str(v_pwd))

    print(f"[C] cashier.payVerified 余额支付 → SUCCESS（订单 {oid1} 金额 {amt1} 分）")
    rb0, pb0 = ride_balance(p), pp_balance(p)
    db0, dpb0 = ride_balance(d), pp_balance(d)
    st, pay1 = req(RIDE, "POST", "/api/cashier/pay",
                   {"userId": p, "orderId": oid1, "method": "BALANCE", "verifyToken": v_pwd["verifyToken"]})
    check("cashier.payVerified 受理 200", st == 200, f"st={st} r={pay1}")
    pid1 = pay1.get("ppPaymentId")
    check("返回 pp 支付单号且终态 SUCCESS", bool(pid1) and pay1.get("ppStatus") == "SUCCESS", str(pay1))
    check("幂等键为 PAY-{orderId}", pay1.get("requestId") == f"PAY-{oid1}", str(pay1.get("requestId")))
    check("首次受理 repeated=false", pay1.get("repeated") is False, str(pay1.get("repeated")))
    res = poll(lambda: ok(RIDE, "GET", f"/api/cashier/result?userId={p}&orderId={oid1}"),
               lambda r: r.get("orderStatus") == "PAID", timeout=60)
    check("结果页轮询：订单 PAID + pp 支付单 SUCCESS",
          res.get("orderStatus") == "PAID" and res.get("ppPaymentStatus") == "SUCCESS", str(res))
    check("乘客双账本扣款一致", ride_balance(p) - rb0 == pp_balance(p) - pb0 == -amt1,
          f"rideΔ={ride_balance(p)-rb0} ppΔ={pp_balance(p)-pb0} amt={amt1}")
    check("司机双账本入账一致且为正", ride_balance(d) - db0 == pp_balance(d) - dpb0 > 0,
          f"rideΔ={ride_balance(d)-db0} ppΔ={pp_balance(d)-dpb0}")
    led = ok(RIDE, "GET", f"/api/wallets/{p}/ledger?orderId={oid1}")["items"]
    debit = next((e for e in led if e["bizType"] == "PAY_DEBIT"), None)
    check("本地流水 CONFIRMED 且 pp_receipt=支付单号",
          debit and debit["status"] == "CONFIRMED" and debit["ppReceipt"] == pid1, str(debit))
    pp_pay = ok(PAY, "GET", f"/api/payments/{pid1}")
    check("pp 支付单 SUCCESS 且金额一致", pp_pay["status"] == "SUCCESS" and pp_pay["amount"] == amt1, str(pp_pay))

    print("[D] 幂等：收银台重复提交 + 订单域 /pay 均不重复扣款")
    rb1, pb1 = ride_balance(p), pp_balance(p)
    st, pay_dup = req(RIDE, "POST", "/api/cashier/pay", {"userId": p, "orderId": oid1, "method": "BALANCE"})
    check("重复提交 200 且 repeated=true", st == 200 and pay_dup.get("repeated") is True, f"st={st} r={pay_dup}")
    check("重复提交返回同一支付单", pay_dup.get("ppPaymentId") == pid1, f"{pay_dup.get('ppPaymentId')} vs {pid1}")
    ok(RIDE, "POST", f"/api/orders/{oid1}/pay")
    time.sleep(2)
    check("重复提交与订单域 /pay 后余额分毫不动", ride_balance(p) == rb1 and pp_balance(p) == pb1,
          f"ride={ride_balance(p)}/{rb1} pp={pp_balance(p)}/{pb1}")

    print("[E] CARD 支付：卡号仅后四位（BIN 亦抹除）")
    o2 = new_trip(p, d, "E")
    oid2, amt2 = o2["id"], o2["actualFee"]
    v2 = cashier_verify(p, oid2, "PASSWORD", payPassword=pwd)
    st, pay2 = req(RIDE, "POST", "/api/cashier/pay",
                   {"userId": p, "orderId": oid2, "method": "BANKCARD", "cardNo": "6222020202020202",
                    "verifyToken": v2["verifyToken"]})
    check("BANKCARD 归一为 CARD 且受理成功", st == 200 and pay2.get("method") == "CARD", f"st={st} r={pay2}")
    check("响应卡号脱敏为 **********0202", pay2.get("cardNo") == "**********0202", str(pay2.get("cardNo")))
    check("全卡号不出现在任何响应字段", "6222020202020202" not in json.dumps(pay2), str(pay2))
    pp_pay2 = ok(PAY, "GET", f"/api/payments/{pay2['ppPaymentId']}")
    check("pp 支付单卡号同样仅后四位", pp_pay2.get("cardNo") == "**********0202", str(pp_pay2.get("cardNo")))

    print("[F] 主扫：cashier.code.create(MERCHANT_PRESENTED) → parse → scanPay")
    o3 = new_trip(p, d, "F")
    oid3, amt3 = o3["id"], o3["actualFee"]
    mc = ok(RIDE, "POST", "/api/cashier/code", {"userId": p, "orderId": oid3, "type": "MERCHANT_PRESENTED"})
    check("主扫码 MP 前缀且带 qrContent", str(mc.get("code", "")).startswith("MP") and bool(mc.get("qrContent")), str(mc))
    parsed = ok(RIDE, "POST", "/api/cashier/code/parse", {"code": mc["qrContent"]})
    check("code.parse 金额/订单一致且不核销",
          parsed["amount"] == amt3 and parsed["status"] == "ACTIVE" and parsed.get("rideOrderId") == oid3, str(parsed))
    v3 = cashier_verify(p, oid3, "PASSWORD", payPassword=pwd)
    rb3, pb3 = ride_balance(p), pp_balance(p)
    st, pay3 = req(RIDE, "POST", "/api/cashier/scan-pay",
                   {"userId": p, "orderId": oid3, "code": mc["qrContent"], "method": "BALANCE",
                    "verifyToken": v3["verifyToken"]})
    check("cashier.scanPay 主扫受理 SUCCESS", st == 200 and pay3.get("ppStatus") == "SUCCESS", f"st={st} r={pay3}")
    check("主扫扣款双账本一致", ride_balance(p) - rb3 == pp_balance(p) - pb3 == -amt3,
          f"rideΔ={ride_balance(p)-rb3} ppΔ={pp_balance(p)-pb3}")
    q = ok(RIDE, "GET", f"/api/cashier/code/{mc['code']}")
    check("code.query 核销后 CONSUMED", q["status"] == "CONSUMED", str(q))
    st, r = req(RIDE, "POST", "/api/cashier/scan-pay",
                {"userId": p, "orderId": oid3, "code": mc["qrContent"], "method": "BALANCE"})
    check("已核销码再受理不重复扣款（幂等或 409）",
          (st == 200 and r.get("repeated") is True) or st == 409, f"st={st} r={r}")

    print("[G] 被扫：cashier.code.create(USER_PRESENTED) → 出示 → scanPay 核销")
    o4 = new_trip(p, d, "G")
    oid4, amt4 = o4["id"], o4["actualFee"]
    uc = ok(RIDE, "POST", "/api/cashier/code", {"userId": p, "orderId": oid4, "type": "USER_PRESENTED"})
    check("被扫码 18 位数字 + displayCode 分组 + ttl",
          len(str(uc.get("code", ""))) == 18 and str(uc["code"]).isdigit() and " " in uc.get("displayCode", "")
          and uc.get("ttlSeconds", 0) > 0, str(uc))
    check("被扫码 qrContent 等于码值（可绘 QR/条码）", uc.get("qrContent") == uc.get("code"), str(uc.get("qrContent")))
    q0 = ok(RIDE, "GET", f"/api/cashier/code/{uc['code']}")
    check("code.query 初始 ACTIVE", q0["status"] == "ACTIVE", str(q0))
    st, pay4 = req(RIDE, "POST", "/api/cashier/scan-pay",
                   {"userId": p, "orderId": oid4, "code": uc["code"], "method": "BALANCE", "payPassword": pwd})
    check("被扫内联口令受理 SUCCESS（口令不落库）", st == 200 and pay4.get("ppStatus") == "SUCCESS", f"st={st} r={pay4}")
    q1 = ok(RIDE, "GET", f"/api/cashier/code/{uc['code']}")
    check("被扫码核销后 CONSUMED", q1["status"] == "CONSUMED", str(q1))
    check("被扫支付金额与订单一致", pay4.get("amount") == amt4, f"{pay4.get('amount')} vs {amt4}")

    print("[H] 付款码过期：等待 TTL 到期 → code.query EXPIRED → scanPay 409")
    o5 = new_trip(p, d, "H")
    oid5 = o5["id"]
    ec = ok(RIDE, "POST", "/api/cashier/code", {"userId": p, "orderId": oid5, "type": "USER_PRESENTED"})
    ttl = ec.get("ttlSeconds", 60)
    print(f"  … 等待付款码过期（ttl={ttl}s）")
    qe = poll(lambda: ok(RIDE, "GET", f"/api/cashier/code/{ec['code']}"),
              lambda x: x["status"] == "EXPIRED", timeout=ttl + 40, interval=3)
    check("到期后 code.query 自然流转 EXPIRED", qe["status"] == "EXPIRED", str(qe))
    rb5 = ride_balance(p)
    st, r = req(RIDE, "POST", "/api/cashier/scan-pay",
                {"userId": p, "orderId": oid5, "code": ec["code"], "method": "BALANCE", "payPassword": pwd})
    check("过期码受理返回 409 A0409", st == 409 and r.get("code") == "A0409", f"st={st} r={r}")
    check("过期码受理不扣款", ride_balance(p) == rb5, f"{ride_balance(p)} vs {rb5}")
    st, r = req(RIDE, "POST", "/api/cashier/code/parse", {"code": "000000000000000000"})
    check("不存在码 parse 返回 404 A0404", st == 404 and r.get("code") == "A0404", f"st={st} r={r}")

    print("[I] 越权与改额防护 + 账面守恒")
    other = ok(RIDE, "POST", "/api/passengers", {"name": f"{TAG}-他人"})["id"]
    st, r = req(RIDE, "GET", f"/api/cashier/methods?userId={other}&orderId={oid1}")
    check("他人订单被拒 A0403", st == 403 and r.get("code") == "A0403", f"st={st} r={r}")
    # 改额防护：携合法令牌但传 amount=1，服务端必须按订单实付受理（而非前端金额）
    v5 = cashier_verify(p, oid5, "PASSWORD", payPassword=pwd)
    st, r = req(RIDE, "POST", "/api/cashier/pay",
                {"userId": p, "orderId": oid5, "amount": 1, "method": "BALANCE", "verifyToken": v5["verifyToken"]})
    check("前端改额无效：按订单实付受理而非 amount=1",
          st == 200 and r.get("amount") == o5["actualFee"], f"st={st} got={r.get('amount')} want={o5['actualFee']}")
    if st == 200 and r.get("ppPaymentId"):
        pp5 = ok(PAY, "GET", f"/api/payments/{r['ppPaymentId']}")
        check("pp 支付单金额同样为订单实付", pp5["amount"] == o5["actualFee"], str(pp5.get("amount")))
    audit = ok(RIDE, "GET", "/api/admin/audit", headers={"X-Admin-Token": "ride-admin"})
    check("ride 账面全部守恒", all(a["ok"] for a in audit), str([a for a in audit if not a["ok"]]))
    mirror = ok(RIDE, "GET", "/api/admin/mirror", headers={"X-Admin-Token": "ride-admin"})
    check("镜像队列无积压无 DEAD", mirror["pendingCount"] == 0 and mirror["deadCount"] == 0, str(mirror))
    rep = poll(lambda: ok(PAY, "POST", "/api/recon/run"), lambda x: x["pass"], timeout=60, interval=2)
    check("payment-pro 对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    print(f"证据：乘客={p} 订单={oid1}/{oid2}/{oid3}/{oid4} 支付单={pid1}/{pay2.get('ppPaymentId')}/{pay3.get('ppPaymentId')}/{pay4.get('ppPaymentId')}")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()