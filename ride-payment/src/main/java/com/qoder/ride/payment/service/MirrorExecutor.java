package com.qoder.ride.payment.service;

import com.qoder.ride.payment.client.PaymentProClient;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
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
            ppPaymentId = client.tradePay(ppOrderId, passengerAccount, row.getRefKey(),
                    client.getCallbackBaseUrl() + "/api/callbacks/payment-pro");
            mirrorMapper.updatePp(row.getBizNo(), ppOrderId, ppPaymentId);
        }
        if (driverCredit.getAmount() > 0) {
            client.transfer(merchantAccount, driverAccount, driverCredit.getAmount(),
                    "PAY-DRV-" + row.getRefKey(), "司机分成");
        }
        return ppPaymentId;
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