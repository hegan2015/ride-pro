package com.qoder.ride.gateway.controller;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.facade.order.OrderDTO;
import com.qoder.ride.facade.order.OrderFacade;
import com.qoder.ride.facade.payment.CashierRequest;
import com.qoder.ride.facade.payment.PaymentFacade;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 乘客端收银台（payment-pro 开放能力由 ride 渲染，pp 不自带 C 端 H5）。
 *
 * 鉴权与会话：沿用 ride 乘客端既有会话（localStorage 乘客 ID → userId 入参），
 * 每个端点校验「订单归属该乘客」，金额/乘客/司机一律由订单域回填，前端不可指定，避免越权与改额。
 * 密钥边界：pp appId/appSecret 只存在于 ride-payment 后端配置，本控制器与前端永不接触。
 * 幂等：requestId 由 ride 侧确定性生成（PAY-{orderId}），前端不传；重复提交返回同一支付单。
 * 既有 /api/wallets/** 端点与语义完全不变，本控制器为 additive 新文件。
 */
@RestController
@RequestMapping("/api/cashier")
public class CashierController {

    @DubboReference
    private PaymentFacade paymentFacade;

    @DubboReference
    private OrderFacade orderFacade;

    /** 沙箱短信明文开关（仅联调）：关闭后即便前端传 sandbox=true 也按掩码下发。 */
    @Value("${gateway.cashier-sandbox-enabled:true}")
    private boolean sandboxEnabled;

    /** 支付方式列表：pp 9 方式 + 实付/免密限额，附 ride 订单金额与优惠明细供收银台头部渲染。 */
    @GetMapping("/methods")
    public ResponseEntity<Object> methods(@RequestParam String userId, @RequestParam String orderId) {
        OrderDTO order = ownOrder(userId, orderId);
        CashierRequest req = base(userId, order);
        Map<String, Object> data = new LinkedHashMap<>(paymentFacade.cashierMethods(req).orThrow());
        data.put("rideOrder", rideOrder(order));
        return ResponseEntity.ok(data);
    }

    /** 创建付款码：USER_PRESENTED（乘客出示，商户扫）/ MERCHANT_PRESENTED（商户出码，乘客扫）。 */
    @PostMapping("/code")
    public ResponseEntity<Object> codeCreate(@RequestBody CashierRequest body) {
        OrderDTO order = ownOrder(body.getUserId(), body.getOrderId());
        CashierRequest req = base(body.getUserId(), order);
        req.setType(body.getType());
        return GatewaySupport.of(paymentFacade.cashierCodeCreate(req));
    }

    /** 解析扫码内容（只读不核销）：前端确认商户/金额/订单后再受理。 */
    @PostMapping("/code/parse")
    public ResponseEntity<Object> codeParse(@RequestBody Map<String, String> body) {
        return GatewaySupport.of(paymentFacade.cashierCodeParse(body.get("code")));
    }

    /** 查询码状态（ACTIVE/CONSUMED/EXPIRED + ttlSeconds），供付款码页倒计时与轮询。 */
    @GetMapping("/code/{code}")
    public ResponseEntity<Object> codeQuery(@PathVariable String code) {
        return GatewaySupport.of(paymentFacade.cashierCodeQuery(code));
    }

    /** 主扫/被扫受理：核销付款码 + 验证 + 幂等支付。 */
    @PostMapping("/scan-pay")
    public ResponseEntity<Object> scanPay(@RequestBody CashierRequest body) {
        OrderDTO order = ownOrder(body.getUserId(), body.getOrderId());
        CashierRequest req = payBody(body, order);
        req.setCode(body.getCode());
        return GatewaySupport.of(paymentFacade.cashierScanPay(req));
    }

    /** 支付验证（口令/短信/指纹/小额免密）：pass=false 返回 200 携原因，由前端分支引导。 */
    @PostMapping("/verify")
    public ResponseEntity<Object> verify(@RequestBody CashierRequest body) {
        OrderDTO order = ownOrder(body.getUserId(), body.getOrderId());
        CashierRequest req = base(body.getUserId(), order);
        req.setVerifyType(body.getVerifyType());
        req.setPayPassword(body.getPayPassword());
        req.setSmsCode(body.getSmsCode());
        req.setBioToken(body.getBioToken());
        return GatewaySupport.of(paymentFacade.cashierVerify(req));
    }

    /** 验证后支付（余额/银行卡）：amount ≤ freeVerifyLimit 可不带验证要素（小额免密）。 */
    @PostMapping("/pay")
    public ResponseEntity<Object> pay(@RequestBody CashierRequest body) {
        OrderDTO order = ownOrder(body.getUserId(), body.getOrderId());
        return GatewaySupport.of(paymentFacade.cashierPayVerified(payBody(body, order)));
    }

    /** 设置/重置支付口令（6 位数字）：口令只入不出，不落库不回显不入日志。 */
    @PostMapping("/pay-password")
    public ResponseEntity<Object> payPassword(@RequestBody CashierRequest body) {
        requireUser(body.getUserId());
        CashierRequest req = new CashierRequest();
        req.setUserId(body.getUserId());
        req.setPassword(body.getPassword());
        req.setOldPassword(body.getOldPassword());
        return GatewaySupport.of(paymentFacade.cashierPayPasswordSet(req));
    }

    /** 发送短信验证码：默认掩码（smsCode=******），仅 sandbox=true 且开关允许时返沙箱明文。 */
    @PostMapping("/sms")
    public ResponseEntity<Object> sms(@RequestBody CashierRequest body) {
        OrderDTO order = ownOrder(body.getUserId(), body.getOrderId());
        CashierRequest req = base(body.getUserId(), order);
        req.setSandbox(sandboxEnabled && Boolean.TRUE.equals(body.getSandbox()));
        return GatewaySupport.of(paymentFacade.cashierSmsSend(req));
    }

    /** 结果页轮询：ride 划转留痕 + pp 支付单终态 + 订单状态，处理中由前端继续轮询。 */
    @GetMapping("/result")
    public ResponseEntity<Object> result(@RequestParam String userId, @RequestParam String orderId) {
        OrderDTO order = ownOrder(userId, orderId);
        Map<String, Object> data = new LinkedHashMap<>(paymentFacade.cashierReceipt(orderId).orThrow());
        data.put("orderStatus", order.getStatus());
        data.put("paidAt", order.getPaidAt());
        return ResponseEntity.ok(data);
    }

    // ---------------- 内部 ----------------

    /** 会话归属校验：订单必须属于该乘客（沿用 ride 乘客登录态，不引入新鉴权体系）。 */
    private OrderDTO ownOrder(String userId, String orderId) {
        requireUser(userId);
        if (orderId == null || orderId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId 必填");
        }
        OrderDTO order = orderFacade.get(orderId).orThrow();
        if (!userId.equals(order.getPassengerId())) {
            throw new BizException("A0403", "订单不属于当前乘客，禁止操作");
        }
        return order;
    }

    /** 金额/乘客/司机由订单域回填（前端传值一律忽略），防止篡改金额或代付他人订单。 */
    private CashierRequest base(String userId, OrderDTO order) {
        CashierRequest req = new CashierRequest();
        req.setUserId(userId);
        req.setOrderId(order.getId());
        req.setPassengerId(order.getPassengerId());
        req.setDriverId(order.getDriverId());
        req.setAmount(order.getActualFee());
        return req;
    }

    private CashierRequest payBody(CashierRequest body, OrderDTO order) {
        CashierRequest req = base(body.getUserId(), order);
        req.setMethod(body.getMethod());
        req.setVerifyType(body.getVerifyType());
        req.setVerifyToken(body.getVerifyToken());
        req.setPayPassword(body.getPayPassword());
        req.setCardNo(body.getCardNo());
        return req;
    }

    private Map<String, Object> rideOrder(OrderDTO order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("status", order.getStatus());
        map.put("originName", order.getOriginName());
        map.put("destName", order.getDestName());
        map.put("driverName", order.getDriverName());
        map.put("estimateFee", order.getEstimateFee());
        map.put("actualFee", order.getActualFee());
        map.put("tipFee", order.getTipFee());
        map.put("fare", order.getFare());
        map.put("actualFare", order.getActualFare());
        map.put("distanceKm", order.getActualDistanceKm() == null ? order.getDistanceKm() : order.getActualDistanceKm());
        return map;
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "userId 必填（乘客登录态）");
        }
    }
}