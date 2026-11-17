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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 双账本镜像投递：单线程按 created_at 顺序把本地资金操作镜像到 payment-pro（HTTPS OpenAPI）。
 * 顺序投递保证充值先于支付、支付先于退款；每步 bizNo 确定性幂等，PAY 多步断点续传；
 * 失败退避重试，耗尽置 DEAD 由监控告警、管理端重放。本地账本不受镜像失败影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MirrorDispatcher {

    private final MirrorOutboxMapper mirrorMapper;
    private final LedgerMapper ledgerMapper;
    private final PaymentProIntegration integration;
    private final PaymentProClient client;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${payment.mirror-interval-ms:1000}")
    public void deliver() {
        if (!integration.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MirrorOutboxDO row : mirrorMapper.selectPending(now, 20)) {
            try {
                mirror(row);
                mirrorMapper.markSent(row.getBizNo());
                log.info("镜像投递成功 bizNo={} type={} retries={}", row.getBizNo(), row.getBizType(), row.getRetries());
            } catch (Exception e) {
                int retries = row.getRetries() + 1;
                String error = truncate(e.getMessage());
                if (retries >= properties.getOutboxMaxRetries()) {
                    mirrorMapper.markDead(row.getBizNo(), error);
                    log.error("镜像重试耗尽置 DEAD bizNo={} retries={}: {}", row.getBizNo(), retries, error);
                } else {
                    mirrorMapper.markRetry(row.getBizNo(), now + backoff(retries), error);
                    log.warn("镜像投递失败退避重试 bizNo={} retries={}: {}", row.getBizNo(), retries, error);
                }
            }
        }
    }

    private void mirror(MirrorOutboxDO row) {
        List<LedgerEntryDO> legs = ledgerMapper.selectByRequestId(row.getRefKey());
        if (legs.isEmpty()) {
            throw new IllegalStateException("镜像回查无流水 refKey=" + row.getRefKey());
        }
        switch (row.getBizType()) {
            case "TOPUP" -> {
                LedgerEntryDO credit = legs.get(0);
                client.topup(integration.ensureAccount(credit.getUserId()), credit.getAmount(), row.getRefKey());
            }
            case "PAY" -> mirrorPay(row, legs);
            case "REFUND" -> mirrorRefund(row, legs);
            case "WITHDRAW" -> {
                LedgerEntryDO debit = legs.get(0);
                client.withdraw(integration.ensureAccount(debit.getUserId()), -debit.getAmount(), row.getRefKey());
            }
            case "TRANSFER" -> {
                LedgerEntryDO debit = legs.stream().filter(l -> l.getAmount() < 0).findFirst().orElseThrow();
                LedgerEntryDO credit = legs.stream().filter(l -> l.getAmount() > 0).findFirst().orElseThrow();
                client.transfer(integration.ensureAccount(debit.getUserId()),
                        integration.ensureAccount(credit.getUserId()), credit.getAmount(), row.getRefKey(), "划转镜像");
            }
            default -> throw new IllegalStateException("未知镜像类型 " + row.getBizType());
        }
    }

    /** 支付镜像：trade.create → trade.pay（乘客扣款入商户）→ 商户划转司机净额；手续费留存商户=平台。 */
    private void mirrorPay(MirrorOutboxDO row, List<LedgerEntryDO> legs) {
        LedgerEntryDO debit = legs.stream().filter(l -> "PAY_DEBIT".equals(l.getBizType())).findFirst().orElseThrow();
        LedgerEntryDO driverCredit = legs.stream().filter(l -> "PAY_CREDIT".equals(l.getBizType())).findFirst().orElseThrow();
        long amount = -debit.getAmount();
        String passengerAccount = integration.ensureAccount(debit.getUserId());
        String driverAccount = integration.ensureAccount(driverCredit.getUserId());
        // 商户余额基线必须在 trade.pay 入账之前落映射，否则快照会把本笔金额重复计入
        String merchantAccount = integration.ensureAccount(properties.getPlatformUserId());

        String ppOrderId = row.getPpOrderId();
        if (ppOrderId == null) {
            ppOrderId = client.tradeCreate("ride-" + row.getOrderId(), amount);
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
    }

    /** 退款镜像：按本地实际借贷腿划转——司机追回入商户，商户全额退乘客（平台兜底部分由商户净 debit 体现）。 */
    private void mirrorRefund(MirrorOutboxDO row, List<LedgerEntryDO> legs) {
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
    }

    private long backoff(int retries) {
        return Math.min(30000L, 1000L << Math.min(retries, 5));
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }
}