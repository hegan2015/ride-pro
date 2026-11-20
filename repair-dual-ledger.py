#!/usr/bin/env python3
"""双账本历史缺口冲正（用户已拍板执行）。

修复两类历史缺陷，只增冲正腿/红冲凭证，不改不删原行：
  A. CARD 误扣乘客钱包（O59/O62/O69，各 1611 分）：pp 银行卡支付不扣 BALANCE 户是正确的，
     ride 当时却扣了乘客钱包。冲正 = 乘客 +1611 / 外部清算户 SYS_CARD −1611，司机与平台不动。
  B. pp 终态 FAILED 但 ride 已确认（O63/P1804，1611 分）：pp 补单收敛置 FAILED（xid 缺失），
     而 ride 旧代码拿到单号即确认，且 pp 旧代码仍把司机分成 1289 划了出去。
     冲正 = ride 三腿全反（P7 +1611 / D1 −1289 / SYS_PLATFORM −322）+ pp 红冲司机分成划转。
最后在全局静默窗口重算被污染的基线快照。

口令从 ~/.config/payment-pro/config.properties 读取，绝不拼进 argv。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

PP = "http://127.0.0.1:8080"
RIDE = "http://127.0.0.1:8090"
ADMIN = {"X-Admin-Token": "ride-admin"}
CARD_ORDERS = [("O59", "P1799"), ("O62", "P1802"), ("O69", "P1830")]
FAILED_ORDER = ("O63", "P1804")
AMOUNT = 1611
DRIVER_CREDIT = 1289
PLATFORM_FEE = 322
CLEARING = "SYS_CARD"


def pp_password():
    path = os.path.expanduser("~/.config/payment-pro/config.properties")
    cfg = {}
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    return cfg.get("PP_ADMIN_PASSWORD") or os.environ.get("PP_ADMIN_PASSWORD") or "admin123"


def call(base, method, path, body=None, headers=None):
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            try:
                return resp.status, json.loads(raw)
            except Exception:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)


def health(base, expect):
    st, d = call(base, "GET", "/api/health")
    arr = d if isinstance(d, list) else (d.get("services", []) if isinstance(d, dict) else [])
    ok = sum(1 for x in arr if x.get("ok"))
    return ok, len(arr)


def ride_wallets():
    st, d = call(RIDE, "GET", "/api/admin/wallets", headers=ADMIN)
    arr = d.get("items") if isinstance(d, dict) else d
    return {str(x["userId"]): x["balance"] for x in (arr or [])}


def pp_balance(tok, acct):
    st, d = call(PP, "GET", f"/api/accounts/{acct}", headers={"Authorization": "Bearer " + tok})
    return d.get("balance") if st == 200 else None


def snapshot(tok, label):
    w = ride_wallets()
    p = {a: pp_balance(tok, a) for a in ("A1207", "A32", "A1")}
    print(f"\n--- {label} ---")
    print(f"  ride  P7={w.get('P7')}  D1={w.get('D1')}  D11={w.get('D11')}  SYS_PLATFORM={w.get('SYS_PLATFORM')}  SYS_CARD={w.get(CLEARING)}")
    print(f"  pp    A1207(P7)={p['A1207']}  A32(D1)={p['A32']}  A1(商户)={p['A1']}")
    return w, p


def main():
    ok, total = health(PP, 13)
    print(f"pp 健康 {ok}/{total}")
    if ok != total:
        print("pp 未全绿，中止（跨系统门禁前须确认对端在线）")
        return 1
    ok, total = health(RIDE, 9)
    print(f"ride 健康 {ok}/{total}")
    if ok != total:
        print("ride 未全绿，中止")
        return 1

    st, login = call(PP, "POST", "/api/auth/login", {"username": "admin", "password": pp_password()})
    if st != 200 or not isinstance(login, dict) or not login.get("token"):
        print(f"pp 登录失败 status={st}")
        return 1
    tok = login["token"]
    auth = {"Authorization": "Bearer " + tok}

    before_w, before_p = snapshot(tok, "冲正前")

    # ---- B 的 pp 侧：红冲 O63 的司机分成划转（pp 支付 FAILED 却把 1289 付给了司机）----
    st, step = call(PP, "POST", "/api/security/step-up", {"password": pp_password()}, auth)
    if st != 200 or not isinstance(step, dict) or not step.get("token"):
        print(f"pp 二次认证失败 status={st} body={str(step)[:200]}")
        return 1
    step_h = dict(auth)
    step_h["X-Step-Up-Token"] = step["token"]
    orig = "PAY-DRV-PAY-O63"
    st, rev = call(PP, "POST", f"/api/accounting/transfers/{orig}/reverse",
                   {"reason": "O63/P1804 支付终态 FAILED（补单收敛:无分布式事务记录），司机分成不应支付，红冲追回",
                    "requestId": "FIX-O63-DRV-CLAW"}, step_h)
    print(f"\npp 红冲 {orig} -> {st} {json.dumps(rev, ensure_ascii=False)[:300] if isinstance(rev, (dict, list)) else str(rev)[:300]}")
    if st not in (200, 409):
        print("pp 红冲未成功，中止（不继续 ride 侧，避免单边冲正造成新的不平）")
        return 1

    # ---- ride 侧冲正 ----
    corrections = []
    for order, pp_id in CARD_ORDERS:
        corrections.append({
            "requestId": f"FIX-CARD-{order}",
            "orderId": order,
            "reason": f"{order}/{pp_id} 实为 CARD 银行卡支付，pp 未扣 BALANCE 户；历史误扣乘客钱包，改由外部清算户承担",
            "legs": [
                {"userId": "P7", "bizType": "CARD_FUNDING_FIX", "amount": AMOUNT},
                {"userId": CLEARING, "bizType": "CARD_FUNDING_FIX", "amount": -AMOUNT},
            ],
        })
    corrections.append({
        "requestId": "FIX-FAILED-O63",
        "orderId": "O63",
        "reason": "O63/P1804 pp 终态 FAILED（补单收敛:无分布式事务记录，一阶段未执行），本地误确认，三腿全额冲正",
        "legs": [
            {"userId": "P7", "bizType": "TERMINAL_FAILED_FIX", "amount": AMOUNT},
            {"userId": "D1", "bizType": "TERMINAL_FAILED_FIX", "amount": -DRIVER_CREDIT},
            {"userId": "SYS_PLATFORM", "bizType": "TERMINAL_FAILED_FIX", "amount": -PLATFORM_FEE},
        ],
    })

    print("\n=== ride 冲正 ===")
    for c in corrections:
        st, d = call(RIDE, "POST", "/api/admin/ledger/correct", c, ADMIN)
        brief = json.dumps(d, ensure_ascii=False)[:260] if isinstance(d, (dict, list)) else str(d)[:260]
        print(f"  {c['requestId']:20s} -> {st} {brief}")
        if st != 200:
            print("  冲正失败，中止")
            return 1
        # 幂等复提：必须 repeated=true 且余额不再变化
        st2, d2 = call(RIDE, "POST", "/api/admin/ledger/correct", c, ADMIN)
        rep = isinstance(d2, dict) and d2.get("repeated") is True
        print(f"    幂等复提 -> {st2} repeated={rep}")
        if st2 != 200 or not rep:
            print("    幂等性未成立，中止")
            return 1

    after_w, after_p = snapshot(tok, "冲正后")

    print("\n=== 逐户对账断言 ===")
    failures = []
    checks = [
        ("P7 ↔ pp A1207", after_w.get("P7"), after_p["A1207"]),
        ("D1 ↔ pp A32", after_w.get("D1"), after_p["A32"]),
    ]
    for name, ride_v, pp_v in checks:
        good = ride_v == pp_v
        print(f"  {'✓' if good else '✗'} {name}: ride={ride_v} pp={pp_v}")
        if not good:
            failures.append(name)
    expect_p7 = before_w.get("P7", 0) + 4 * AMOUNT
    good = after_w.get("P7") == expect_p7
    print(f"  {'✓' if good else '✗'} P7 增量 = 4×{AMOUNT} = {4*AMOUNT}: {before_w.get('P7')} → {after_w.get('P7')}（期望 {expect_p7}）")
    if not good:
        failures.append("P7 增量")
    good = after_w.get(CLEARING) == -3 * AMOUNT
    print(f"  {'✓' if good else '✗'} SYS_CARD 承担 3 笔 CARD 出资 = {-3*AMOUNT}: 实际 {after_w.get(CLEARING)}")
    if not good:
        failures.append("SYS_CARD")

    st, audit = call(RIDE, "GET", "/api/admin/overview", headers=ADMIN)
    print(f"\nride overview -> {st}")

    if failures:
        print(f"\n对账未通过: {failures}（不重算基线，避免掩盖缺口）")
        return 1

    # ---- 基线重算（须全局静默）----
    print("\n=== 基线重算（静默窗口）===")
    st, d = call(RIDE, "POST", "/api/admin/dual-ledger/rebaseline", {}, ADMIN)
    print(f"  -> {st}")
    if st != 200:
        print(f"  重算被拒: {json.dumps(d, ensure_ascii=False)[:300] if isinstance(d, dict) else str(d)[:300]}")
        return 1
    for row in d.get("accounts", []):
        print(f"    {row['userId']:14s} pp={row['ppBalance']:>10} 净额={row['mirroredNet']:>10} 基线 {row['oldSnapshot']} → {row['newSnapshot']}")

    print("\n=== 等两个监控周期后复核 ===")
    time.sleep(65)
    st, d = call(RIDE, "GET", "/api/admin/wallets", headers=ADMIN)
    print("  冲正与基线重算完成。请核 logs/payment.log 是否仍有『双账本不平』。")
    return 0


if __name__ == "__main__":
    sys.exit(main())