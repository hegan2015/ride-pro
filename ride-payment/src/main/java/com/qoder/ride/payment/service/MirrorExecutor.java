package com.qoder.ride.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qoder.ride.payment.client.PaymentProClient;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.client.PaymentProRejectedException;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.CashierCodeDO;
import com.qoder.ride.payment.dataobject.CashierRequestDO;
import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import com.qoder.ride.payment.mapper.CashierCodeMapper;
import com.qoder.ride.payment.mapper.CashierRequestMapper;
import com.qoder.ride.payment.mapper.LedgerMapper;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * payment-pro 同步调用执行器：把一笔 PENDING 流水对应的资金操作打到 payment-pro OpenAPI，
 * 返回回执号。同步请求路径与崩溃恢复路径共用；每步 bizNo/outTradeNo 确定性幂等，
 * PAY 多步（下单→支付→分成）以 pp_order_id/pp_payment_id 断点续传。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MirrorExecutor {

    private final MirrorOutboxMapper mirrorMapper;
    private final LedgerMapper ledgerMapper;
    private final PaymentProIntegration integration;
    private final PaymentProClient client;
    private final PaymentProperties properties;
    private final CashierRequestMapper cashierRequestMapper;
    private final CashierCodeMapper cashierCodeMapper;

    /** 执行成功返回回执号（支付单 ID / 账务幂等键 / 充值流水号）。 */
    public String execute(MirrorOutboxDO row) {
        List<LedgerEntryDO> legs = pendingLegs(row.getRefKey());
        return switch (row.getBizType()) {
            case "TOPUP" -> {
                LedgerEntryDO credit = legs.get(0);
                yield client.topup(integration.ensureAccount(credit.getUserId()), credit.getAmount(), row.getRefKey());
            }
            case "PAY" -> executePay(row, legs);
            case "REFUND" -> executeRefund(row, legs);
            case "WITHDRAW" -> {
                LedgerEntryDO debit = legs.get(0);
                yield client.withdraw(integration.ensureAccount(debit.getUserId()), -debit.getAmount(), row.getRefKey());
            }
            case "TRANSFER" -> {
                LedgerEntryDO debit = legs.stream().filter(l -> l.getAmount() < 0).findFirst().orElseThrow();
                LedgerEntryDO credit = legs.stream().filter(l -> l.getAmount() > 0).findFirst().orElseThrow();
                client.transfer(integration.ensureAccount(debit.getUserId()),
                        integration.ensureAccount(credit.getUserId()), credit.getAmount(), row.getRefKey(), "划转");
                yield row.getRefKey();
            }
            default -> throw new IllegalStateException("未知资金类型 " + row.getBizType());
        };
    }

    public List<LedgerEntryDO> pendingLegs(String refKey) {
        List<LedgerEntryDO> legs = ledgerMapper.selectByRequestId(refKey).stream()
                .filter(l -> "PENDING".equals(l.getStatus())).toList();
        if (legs.isEmpty()) {
            throw new IllegalStateException("无 PENDING 流水 refKey=" + refKey);
        }
        return legs;
    }

    /** 支付：trade.create（outTradeNo 幂等）→ trade.pay（乘客扣款入商户）→ 商户划转司机净额；手续费留存商户=平台。 */
    private String executePay(MirrorOutboxDO row, List<LedgerEntryDO> legs) {
        // 收银台受理留痕存在 → 走 cashier.* 分支（同一 requestId/同一 pp 交易单，不重复下单不重复扣款）
        CashierRequestDO cashier = cashierRequestMapper.selectByRequestId(row.getRefKey());
        if (cashier != null) {
            return executeCashierPay(row, legs, cashier);
        }
        LedgerEntryDO debit = legs.stream().filter(l -> "PAY_DEBIT".equals(l.getBizType())).findFirst().orElseThrow();
        LedgerEntryDO driverCredit = legs.stream().filter(l -> "PAY_CREDIT".equals(l.getBizType())).findFirst().orElseThrow();
        long amount = -debit.getAmount();
        String passengerAccount = integration.ensureAccount(debit.getUserId());
        String driverAccount = integration.ensureAccount(driverCredit.getUserId());
        // 商户余额基线必须在 trade.pay 入账之前落映射，否则快照会把本笔金额重复计入
        String merchantAccount = integration.ensureAccount(properties.getPlatformUserId());

        String ppOrderId = row.getPpOrderId();
        if (ppOrderId == null) {
            ppOrderId = client.tradeCreate("ride-" + row.getOrderId(), amount, row.getRefKey());
            mirrorMapper.updatePp(row.getBizNo(), ppOrderId, null);
        }
        String ppPaymentId = row.getPpPaymentId();
        if (ppPaymentId == null) {
            JsonNode payment = client.tradePay(ppOrderId, passengerAccount, row.getRefKey(),
                    client.getCallbackBaseUrl() + "/api/callbacks/payment-pro");
            requireSettled(payment, row.getRefKey());
            ppPaymentId = payment.path("id").asText();
            mirrorMapper.updatePp(row.getBizNo(), ppOrderId, ppPaymentId);
        } else {
            requireSettled(client.tradeQuery(ppOrderId).path("payment"), row.getRefKey());
        }
        if (driverCredit.getAmount() > 0) {
            client.transfer(merchantAccount, driverAccount, driverCredit.getAmount(),
                    "PAY-DRV-" + row.getRefKey(), "司机分成");
        }
        return ppPaymentId;
    }

    /**
     * 收银台支付分支：pp 交易单已由 CashierService 以同一 outTradeNo 幂等创建，
     * 此处只做「受理」——cashier.payVerified（验证前置）或 cashier.scanPay（核销付款码），
     * requestId 沿用 PAY-{orderId}，pp 侧同键返回同一支付单（repeated=true）不重复扣款。
     * BALANCE 仍是同步受理→PROCESSING→SUCCESS，手续费口径与 trade.pay 一致；随后商户划转司机净额。
     * 崩溃恢复：已记录 pp_payment_id 则直接复用；令牌已核销导致 403/409 时以 trade.query 终态兜底。
     */
    private String executeCashierPay(MirrorOutboxDO row, List<LedgerEntryDO> legs, CashierRequestDO cashier) {
        LedgerEntryDO driverCredit = legs.stream().filter(l -> "PAY_CREDIT".equals(l.getBizType())).findFirst().orElseThrow();
        String passengerAccount = cashier.getPpAccountId();
        String driverAccount = integration.ensureAccount(driverCredit.getUserId());
        // 商户余额基线必须在受理入账之前落映射，否则快照会把本笔金额重复计入
        String merchantAccount = integration.ensureAccount(properties.getPlatformUserId());
        String ppOrderId = cashier.getPpOrderId();
        String callbackUrl = client.getCallbackBaseUrl() + "/api/callbacks/payment-pro";

        String ppPaymentId = cashier.getPpPaymentId();
        if (ppPaymentId == null) {
            try {
                JsonNode result = "SCAN".equals(cashier.getMode())
                        ? client.cashierScanPay(cashier.getCode(), row.getRefKey(),
                                "MERCHANT_PRESENTED".equals(codeType(cashier.getCode())) ? passengerAccount : null,
                                cashier.getMethod(), cashier.getVerifyToken(), cashier.getCardNo(), callbackUrl)
                        : client.cashierPayVerified(ppOrderId, row.getRefKey(), cashier.getMethod(), passengerAccount,
                                cashier.getVerifyToken(), cashier.getCardNo(), callbackUrl);
                JsonNode payment = result.path("payment");
                requireSettled(payment, row.getRefKey());
                ppPaymentId = payment.path("id").asText();
                log.info("收银台受理回执 mode={} requestId={} ppOrder={} ppPayment={} repeated={}",
                        cashier.getMode(), row.getRefKey(), ppOrderId, ppPaymentId,
                        result.path("repeated").asBoolean());
            } catch (PaymentProRejectedException e) {
                // 令牌一次性：崩溃恢复时可能已核销。若 pp 侧该单已支付成功，按幂等回执续传，不重复受理。
                String settled = settledPaymentId(ppOrderId, e);
                if (settled == null) {
                    throw e;
                }
                ppPaymentId = settled;
                log.warn("收银台受理幂等兜底（pp 已支付） requestId={} ppPayment={}", row.getRefKey(), ppPaymentId);
            }
            cashierRequestMapper.markSent(row.getRefKey(), ppPaymentId, System.currentTimeMillis());
            mirrorMapper.updatePp(row.getBizNo(), ppOrderId, ppPaymentId);
        }
        if (driverCredit.getAmount() > 0) {
            client.transfer(merchantAccount, driverAccount, driverCredit.getAmount(),
                    "PAY-DRV-" + row.getRefKey(), "司机分成");
        }
        return ppPaymentId;
    }

    private String codeType(String code) {
        CashierCodeDO local = code == null ? null : cashierCodeMapper.selectByCode(code);
        return local == null ? null : local.getType();
    }

    /**
     * 回执终态守卫：pp 的 BALANCE/收银台受理是「同步受理→PROCESSING→SUCCESS」，
     * 拿到支付单号不等于钱已划转。只有 SUCCESS 才允许本地确认动账；
     * FAILED/CLOSED 属明确拒绝（流水 FAILED、解冻退回）；其余在途态抛非拒绝异常，
     * 让镜像保持 PENDING 交恢复任务续传，杜绝「本地已确认、对端后来改判失败」的永久不平。
     */
    private void requireSettled(JsonNode payment, String refKey) {
        String status = payment.path("status").asText(null);
        if ("SUCCESS".equals(status) || "PARTIAL_REFUNDED".equals(status) || "REFUNDED".equals(status)) {
            return;
        }
        String id = payment.path("id").asText(null);
        if ("FAILED".equals(status) || "CLOSED".equals(status)) {
            throw new PaymentProRejectedException("支付单终态失败 paymentId=" + id + " status=" + status
                    + " reason=" + payment.path("failReason").asText(""));
        }
        throw new IllegalStateException("支付单仍在途，待恢复续传 paymentId=" + id + " status=" + status
                + " refKey=" + refKey);
    }

    /** pp 侧已支付成功的支付单号（仅 403/409 防重放场景兜底），否则 null 交由上层终态失败。 */
    private String settledPaymentId(String ppOrderId, PaymentProRejectedException cause) {
        String message = cause.getMessage();
        if (message == null || !(message.startsWith("HTTP 403") || message.startsWith("HTTP 409"))) {
            return null;
        }
        try {
            JsonNode payment = client.tradeQuery(ppOrderId).path("payment");
            String status = payment.path("status").asText();
            if (payment.hasNonNull("id") && "SUCCESS".equals(status)) {
                return payment.get("id").asText();
            }
        } catch (RuntimeException ignore) {
            // 查询不可用：保持原拒绝语义，交由恢复任务重试
        }
        return null;
    }

    /** 退款：按本地实际借贷腿划转——司机追回入商户，商户全额退乘客（平台兜底部分由商户净 debit 体现）。 */
    private String executeRefund(MirrorOutboxDO row, List<LedgerEntryDO> legs) {
        LedgerEntryDO passengerCredit = legs.stream().filter(l -> "REFUND_CREDIT".equals(l.getBizType())).findFirst().orElseThrow();
        long amount = passengerCredit.getAmount();
        String passengerAccount = integration.ensureAccount(passengerCredit.getUserId());
        String merchantAccount = integration.ensureAccount(properties.getPlatformUserId());
        LedgerEntryDO driverDebit = legs.stream().filter(l -> "REFUND_DEBIT".equals(l.getBizType())).findFirst().orElse(null);
        if (driverDebit != null) {
            String driverAccount = integration.ensureAccount(driverDebit.getUserId());
            client.transfer(driverAccount, merchantAccount, -driverDebit.getAmount(),
                    "RFD-CLAW-" + row.getRefKey(), "退款司机追回");
        }
        client.transfer(merchantAccount, passengerAccount, amount,
                "RFD-" + row.getRefKey(), "退款乘客入账");
        return "RFD-" + row.getRefKey();
    }
}