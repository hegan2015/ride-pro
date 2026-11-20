package com.qoder.ride.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.facade.payment.CashierPayDTO;
import com.qoder.ride.facade.payment.CashierRequest;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.payment.client.PaymentProClient;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.client.PaymentProRejectedException;
import com.qoder.ride.payment.dataobject.CashierCodeDO;
import com.qoder.ride.payment.dataobject.CashierRequestDO;
import com.qoder.ride.payment.mapper.CashierCodeMapper;
import com.qoder.ride.payment.mapper.CashierRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 收银台对接服务（payment-pro cashier.* 9 方法 → ride 乘客端）。
 *
 * 资金语义：收银台只负责「渲染 / 验证 / 受理」，本地划转仍走既有 {@link PaymentService#pay}——
 * PENDING 流水 + 冻结 → 同步 pp 受理 → 回执确认 CONFIRMED 动账 → 域 outbox 通知订单域。
 * 幂等键沿用订单域支付键 <code>PAY-{orderId}</code>：收银台先付后，订单域 /pay 命中幂等不重复扣款。
 * pp 交易单以既有 trade.create（outTradeNo=PAY-{orderId}）幂等创建，与镜像路径同一张单，不产生重复订单。
 *
 * 安全：appSecret 只在 {@link PaymentProClient} 内；支付口令/短信码只入不出、不落库、不入日志；
 * 卡号仅存脱敏形态；verifyToken 一次性落 cashier_request 供崩溃恢复续传。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashierService {

    private static final String MODE_VERIFIED = "VERIFIED";
    private static final String MODE_SCAN = "SCAN";

    private final PaymentProClient client;
    private final PaymentProIntegration integration;
    private final PaymentService paymentService;
    private final CashierRequestMapper requestMapper;
    private final CashierCodeMapper codeMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- 渲染 ----------------

    /** cashier.methods：先幂等确保 pp 交易单，再按付款账户渲染可用方式与免密限额。 */
    public Map<String, Object> methods(CashierRequest req) {
        requireEnabled();
        String orderId = require(req.getOrderId(), "orderId");
        String userId = require(req.getUserId(), "userId");
        long amount = requireAmount(req.getAmount());
        String accountId = integration.ensureAccount(userId);
        String ppOrderId = ensurePpOrder(orderId, amount);
        JsonNode node = guard(() -> client.cashierMethods(ppOrderId, accountId));
        Map<String, Object> out = toMap(node);
        out.put("rideOrderId", orderId);
        out.put("ppOrderId", ppOrderId);
        out.put("accountId", accountId);
        out.put("walletBalance", paymentService.wallet(userId).getBalance());
        return out;
    }

    // ---------------- 付款码 ----------------

    /**
     * cashier.code.create：被扫（USER_PRESENTED）绑付款账户，主扫（MERCHANT_PRESENTED）绑既有商户账户。
     * requestId 确定性：同订单同类型且码仍 ACTIVE 时复用同键（返回同一有效码）；已过期/已核销则换新键重发。
     */
    public Map<String, Object> codeCreate(CashierRequest req) {
        requireEnabled();
        String type = require(req.getType(), "type").trim().toUpperCase();
        if (!"USER_PRESENTED".equals(type) && !"MERCHANT_PRESENTED".equals(type)) {
            throw new BizException(ResultCode.PARAM_ERROR, "type 须为 USER_PRESENTED 或 MERCHANT_PRESENTED");
        }
        String userId = require(req.getUserId(), "userId");
        String orderId = req.getOrderId();
        boolean merchant = "MERCHANT_PRESENTED".equals(type);
        String accountId = merchant ? client.getMerchantAccountId() : integration.ensureAccount(userId);
        String ppOrderId = null;
        Long amount = req.getAmount();
        if (orderId != null && !orderId.isBlank()) {
            amount = requireAmount(req.getAmount());
            ppOrderId = ensurePpOrder(orderId, amount);
        } else if (amount == null || amount <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "orderId 与 amount 至少一项");
        }
        String requestId = codeRequestId(orderId, type);
        final String fAccount = accountId;
        final String fOrder = ppOrderId;
        final Long fAmount = amount;
        JsonNode node = guard(() -> client.cashierCodeCreate(type, fAccount, fOrder, fAmount, requestId));
        long now = System.currentTimeMillis();
        CashierCodeDO row = new CashierCodeDO();
        row.setCode(node.path("code").asText());
        row.setOrderId(orderId);
        row.setUserId(userId);
        row.setPpOrderId(ppOrderId);
        row.setPpAccountId(accountId);
        row.setType(type);
        row.setAmount(node.path("amount").isMissingNode() ? amount : node.path("amount").asLong());
        row.setStatus(node.path("status").asText("ACTIVE"));
        row.setRequestId(requestId);
        row.setExpireAt(node.path("expireAt").asLong(0));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        codeMapper.upsert(row);
        log.info("收银台付款码生成 orderId={} type={} requestId={} ttl={}s",
                orderId, type, requestId, node.path("ttlSeconds").asInt());
        Map<String, Object> out = toMap(node);
        out.put("rideOrderId", orderId);
        out.put("requestId", requestId);
        return out;
    }

    /** cashier.code.parse：只读解析，不核销；附 ride 侧归属订单便于前端确认。 */
    public Map<String, Object> codeParse(String code) {
        requireEnabled();
        String normalized = normalizeCode(code);
        JsonNode node = guard(() -> client.cashierCodeParse(normalized));
        return enrichCode(node);
    }

    /** cashier.code.query：轮询码状态，同步本地留痕（只改状态，不删行）。 */
    public Map<String, Object> codeQuery(String code) {
        requireEnabled();
        String normalized = normalizeCode(code);
        JsonNode node = guard(() -> client.cashierCodeQuery(normalized));
        String status = node.path("status").asText();
        if (codeMapper.selectByCode(normalized) != null && !status.isBlank()) {
            codeMapper.updateStatus(normalized, status, System.currentTimeMillis());
        }
        return enrichCode(node);
    }

    // ---------------- 验证 ----------------

    /**
     * cashier.verify：四型验证。每次尝试用独立 requestId（避免失败会话被幂等复用），
     * pass=false 原样返回（reason/challengeType/remainAttempts/lockedUntil）由前端分支引导，不抛异常。
     */
    public Map<String, Object> verify(CashierRequest req) {
        requireEnabled();
        String userId = require(req.getUserId(), "userId");
        String accountId = integration.ensureAccount(userId);
        String ppOrderId = null;
        if (req.getOrderId() != null && !req.getOrderId().isBlank()) {
            ppOrderId = ensurePpOrder(req.getOrderId(), requireAmount(req.getAmount()));
        }
        String verifyType = blankToNull(req.getVerifyType());
        String attemptKey = "CSHV-" + (req.getOrderId() == null ? accountId : req.getOrderId())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        final String fPpOrderId = ppOrderId;
        final Long fAmount = req.getAmount();
        JsonNode node = guard(() -> client.cashierVerify(accountId, fPpOrderId, fAmount, verifyType,
                blankToNull(req.getPayPassword()), blankToNull(req.getSmsCode()),
                blankToNull(req.getBioToken()), attemptKey));
        log.info("收银台支付验证 userId={} orderId={} type={} pass={} challenge={}", userId, req.getOrderId(),
                verifyType, node.path("pass").asBoolean(), node.path("challengeType").asText());
        Map<String, Object> out = toMap(node);
        out.put("rideOrderId", req.getOrderId());
        out.put("ppOrderId", ppOrderId);
        out.put("accountId", accountId);
        return out;
    }

    /** cashier.payPassword.set：口令只入不出，不落库不入日志。 */
    public Boolean payPasswordSet(CashierRequest req) {
        requireEnabled();
        String accountId = integration.ensureAccount(require(req.getUserId(), "userId"));
        String password = require(req.getPassword(), "password");
        if (!password.matches("\\d{6}")) {
            throw new BizException(ResultCode.PARAM_ERROR, "支付口令须为 6 位数字");
        }
        boolean ok = guard(() -> client.cashierPayPasswordSet(accountId, password, blankToNull(req.getOldPassword())));
        log.info("收银台支付口令已设置 userId={}", req.getUserId());
        return ok;
    }

    /** cashier.sms.send：默认掩码；sandbox=true 才返明文（仅联调，生产链路不得依赖响应取码）。 */
    public Map<String, Object> smsSend(CashierRequest req) {
        requireEnabled();
        String accountId = integration.ensureAccount(require(req.getUserId(), "userId"));
        String ppOrderId = null;
        if (req.getOrderId() != null && !req.getOrderId().isBlank()) {
            ppOrderId = ensurePpOrder(req.getOrderId(), requireAmount(req.getAmount()));
        }
        Boolean sandbox = Boolean.TRUE.equals(req.getSandbox()) ? Boolean.TRUE : null;
        final String fPpOrderId = ppOrderId;
        JsonNode node = guard(() -> client.cashierSmsSend(accountId, fPpOrderId, sandbox));
        log.info("收银台短信验证码已下发 userId={} sandbox={} masked={}", req.getUserId(),
                sandbox != null, node.path("smsCodeMasked").asBoolean(false));
        return toMap(node);
    }

    // ---------------- 受理 ----------------

    /** cashier.payVerified：验证前置受理（余额/银行卡），幂等键 PAY-{orderId}。 */
    public CashierPayDTO payVerified(CashierRequest req) {
        return accept(req, MODE_VERIFIED);
    }

    /** cashier.scanPay：扫码受理（核销码 + 验证 + 幂等支付），幂等键 PAY-{orderId}。 */
    public CashierPayDTO scanPay(CashierRequest req) {
        require(req.getCode(), "code");
        return accept(req, MODE_SCAN);
    }

    /**
     * 受理统一入口：幂等回读 → 确保 pp 单 → 验证令牌化 → 落 cashier_request 留痕 →
     * 复用既有 PaymentService.pay 完成本地冻结/PENDING/回执确认（MirrorExecutor 走收银台分支）。
     */
    private CashierPayDTO accept(CashierRequest req, String mode) {
        requireEnabled();
        String orderId = require(req.getOrderId(), "orderId");
        String passengerId = require(req.getPassengerId(), "passengerId");
        String driverId = require(req.getDriverId(), "driverId");
        long amount = requireAmount(req.getAmount());
        String requestId = payKey(orderId);
        String method = normalizeMethod(req.getMethod());

        PayResultDTO existing = paymentService.payResult(orderId);
        if (existing != null) {
            log.info("收银台受理幂等命中 requestId={}", requestId);
            return receiptDto(orderId, requestId, existing, true);
        }

        String accountId = integration.ensureAccount(passengerId);
        String ppOrderId = ensurePpOrder(orderId, amount);
        String code = mode.equals(MODE_SCAN) ? normalizeCode(req.getCode()) : null;
        String cardNo = blankToNull(req.getCardNo());
        if (MODE_SCAN.equals(mode)) {
            JsonNode parsed = guard(() -> client.cashierCodeParse(code));
            String boundOrder = parsed.path("orderId").asText(null);
            if (boundOrder == null || !boundOrder.equals(ppOrderId)) {
                throw new BizException(ResultCode.CONFLICT, "付款码与订单不匹配，禁止受理");
            }
            CashierCodeDO local = codeMapper.selectByCode(code);
            if (local != null && "USER_PRESENTED".equals(local.getType())
                    && !passengerId.equals(local.getUserId())) {
                throw new BizException(ResultCode.CONFLICT, "付款码非本乘客签发，禁止受理");
            }
        }

        // 内联口令先换成一次性令牌：口令永不落库，崩溃恢复只凭令牌续传
        String verifyToken = blankToNull(req.getVerifyToken());
        String verifyType = blankToNull(req.getVerifyType());
        String inlinePassword = blankToNull(req.getPayPassword());
        if (verifyToken == null && inlinePassword != null) {
            CashierRequest vr = new CashierRequest();
            vr.setUserId(passengerId);
            vr.setOrderId(orderId);
            vr.setAmount(amount);
            vr.setVerifyType("PASSWORD");
            vr.setPayPassword(inlinePassword);
            Map<String, Object> verified = verify(vr);
            if (!Boolean.TRUE.equals(verified.get("pass"))) {
                throw new BizException("A0403", String.valueOf(verified.getOrDefault("reason", "支付验证未通过")));
            }
            verifyToken = String.valueOf(verified.get("verifyToken"));
            verifyType = "PASSWORD";
        }

        long now = System.currentTimeMillis();
        CashierRequestDO row = new CashierRequestDO();
        row.setRequestId(requestId);
        row.setOrderId(orderId);
        row.setUserId(passengerId);
        row.setPpOrderId(ppOrderId);
        row.setPpAccountId(accountId);
        row.setAmount(amount);
        row.setMode(mode);
        row.setMethod(method);
        row.setCode(code);
        row.setCardNo(maskCard(cardNo));
        row.setVerifyType(verifyType);
        row.setVerifyToken(verifyToken);
        row.setStatus("NEW");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        requestMapper.upsert(row);

        PayRequest payRequest = new PayRequest();
        payRequest.setOrderId(orderId);
        payRequest.setPassengerId(passengerId);
        payRequest.setDriverId(driverId);
        payRequest.setAmount(amount);
        payRequest.setRequestId(requestId);
        payRequest.setMethod(method);
        try {
            PayResultDTO result = paymentService.pay(payRequest);
            log.info("收银台受理成功 mode={} orderId={} requestId={} amount={} method={}",
                    mode, orderId, requestId, amount, method);
            return receiptDto(orderId, requestId, result, Boolean.TRUE.equals(result.getRepeated()));
        } catch (BizException e) {
            requestMapper.markFailed(requestId, truncate(e.getMessage()), System.currentTimeMillis());
            throw mapPpError(e.getMessage());
        } catch (RuntimeException e) {
            requestMapper.markFailed(requestId, truncate(e.getMessage()), System.currentTimeMillis());
            throw e;
        }
    }

    /** 受理留痕 + pp 支付单终态快照（结果页轮询用；只读，不产生资金副作用）。 */
    public Map<String, Object> receipt(String orderId) {
        require(orderId, "orderId");
        CashierRequestDO row = requestMapper.selectByRequestId(payKey(orderId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("requestId", payKey(orderId));
        out.put("accepted", row != null);
        if (row != null) {
            out.put("mode", row.getMode());
            out.put("method", row.getMethod());
            out.put("status", row.getStatus());
            out.put("ppOrderId", row.getPpOrderId());
            out.put("ppPaymentId", row.getPpPaymentId());
            out.put("cardNo", row.getCardNo());
            out.put("amount", row.getAmount());
            out.put("lastError", row.getLastError());
            out.put("createdAt", row.getCreatedAt());
        }
        PayResultDTO result = paymentService.payResult(orderId);
        out.put("paid", result != null);
        out.put("payResult", result);
        if (row != null && row.getPpOrderId() != null && client.isEnabled()) {
            try {
                JsonNode query = client.tradeQuery(row.getPpOrderId());
                out.put("ppOrderStatus", query.path("order").path("status").asText());
                out.put("ppPaymentStatus", query.path("payment").path("status").asText(null));
                out.put("ppFee", query.path("payment").path("fee").asLong(0));
            } catch (RuntimeException e) {
                out.put("ppQueryError", "支付单查询暂不可用");
            }
        }
        return out;
    }

    // ---------------- 内部 ----------------

    /** pp 交易单幂等创建：outTradeNo=PAY-{orderId}，与既有镜像路径同一张单（subject 亦一致）。 */
    private String ensurePpOrder(String orderId, long amount) {
        return guard(() -> client.tradeCreate("ride-" + orderId, amount, payKey(orderId)));
    }

    private CashierPayDTO receiptDto(String orderId, String requestId, PayResultDTO result, boolean repeated) {
        CashierRequestDO row = requestMapper.selectByRequestId(requestId);
        CashierPayDTO dto = new CashierPayDTO();
        dto.setRequestId(requestId);
        dto.setOrderId(orderId);
        dto.setPayResult(result);
        dto.setRepeated(repeated);
        dto.setAmount(result.getAmount());
        dto.setFee(result.getFee());
        if (row != null) {
            dto.setPpOrderId(row.getPpOrderId());
            dto.setPpPaymentId(row.getPpPaymentId());
            dto.setMethod(row.getMethod());
            dto.setCardNo(row.getCardNo());
        }
        if (row != null && row.getPpOrderId() != null) {
            try {
                JsonNode query = client.tradeQuery(row.getPpOrderId());
                JsonNode payment = query.path("payment");
                dto.setPpStatus(payment.path("status").asText(query.path("order").path("status").asText()));
                if (payment.hasNonNull("cardNo")) {
                    dto.setCardNo(payment.get("cardNo").asText());
                }
                if (payment.hasNonNull("id")) {
                    dto.setPpPaymentId(payment.get("id").asText());
                }
            } catch (RuntimeException e) {
                dto.setPpStatus("PROCESSING");
            }
        }
        return dto;
    }

    private Map<String, Object> enrichCode(JsonNode node) {
        Map<String, Object> out = toMap(node);
        CashierCodeDO local = codeMapper.selectByCode(node.path("code").asText());
        if (local != null) {
            out.put("rideOrderId", local.getOrderId());
            out.put("rideUserId", local.getUserId());
        }
        return out;
    }

    /** 同订单同类型码仍 ACTIVE 则复用幂等键（返回同一有效码），否则换新键重发。 */
    private String codeRequestId(String orderId, String type) {
        String base = "CSHC-" + (orderId == null ? "AMT" : orderId) + "-" + type;
        if (orderId == null) {
            return base + "-" + System.currentTimeMillis();
        }
        boolean reusable = codeMapper.selectByOrder(orderId).stream()
                .anyMatch(c -> type.equals(c.getType()) && "ACTIVE".equals(c.getStatus())
                        && c.getExpireAt() != null && c.getExpireAt() > System.currentTimeMillis());
        return reusable ? base : base + "-" + System.currentTimeMillis();
    }

    /**
     * 扫码内容归一：主扫码的 qrContent 是扫码 URL（形如 .../scan?code=MPxxx&amount=1611），
     * 真实扫码枪/摄像头识别后需从中取出 code 参数；被扫码的 qrContent 即码值本身。
     * 这里同时接受「裸码值」与「扫码 URL」，前端可原样粘贴二维码内容。
     */
    private static String normalizeCode(String raw) {
        String value = require(raw, "code").trim();
        int idx = value.indexOf("code=");
        if (idx >= 0 && (value.startsWith("http://") || value.startsWith("https://") || value.contains("?"))) {
            String tail = value.substring(idx + 5);
            int end = tail.indexOf('&');
            String extracted = end >= 0 ? tail.substring(0, end) : tail;
            try {
                extracted = java.net.URLDecoder.decode(extracted, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignore) {
                // 非法转义序列：按原样使用，交由 pp 判定
            }
            if (!extracted.isBlank()) {
                return extracted.trim();
            }
        }
        return value;
    }

    private void requireEnabled() {
        if (!client.isEnabled()) {
            throw new BizException(ResultCode.BIZ_ERROR, "payment-pro 集成未配置，收银台不可用");
        }
    }

    /** pp 明确拒绝 → 透传其错误码与文案（A0403/A0409/A0404…），前端据此给明确原因与重试引导。 */
    private <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (PaymentProRejectedException e) {
            throw mapPpError(e.getMessage());
        }
    }

    /** 从 "HTTP 403 {json}" 形态提取 pp 错误码/文案；无法解析则归一为冲突并保留原文。 */
    private BizException mapPpError(String raw) {
        String message = raw == null ? "支付系统拒绝" : raw;
        int brace = message.indexOf('{');
        if (brace >= 0) {
            try {
                JsonNode node = mapper.readTree(message.substring(brace));
                if (node.hasNonNull("code")) {
                    return new BizException(node.get("code").asText(), node.path("error").asText("支付系统拒绝"));
                }
            } catch (Exception ignore) {
                // 非 JSON 响应体：落到下方归一分支
            }
        }
        return new BizException(ResultCode.CONFLICT, message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(node, LinkedHashMap.class);
    }

    private static String payKey(String orderId) {
        return "PAY-" + orderId;
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return "BALANCE";
        }
        String m = method.trim().toUpperCase();
        return "BANKCARD".equals(m) ? "CARD" : m;
    }

    /** 卡号只保留后四位（BIN 亦抹除），与 pp 开放面口径一致；本地留痕同样不落全卡号。 */
    private static String maskCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 4) {
            return null;
        }
        return "*".repeat(cardNo.length() - 4) + cardNo.substring(cardNo.length() - 4);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, name + " 必填");
        }
        return value;
    }

    private static long requireAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "amount 必填且须为正整数（分）");
        }
        return amount;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }
}