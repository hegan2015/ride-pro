package com.qoder.ride.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qoder.ride.facade.payment.OutboxStatsDTO;
import com.qoder.ride.facade.payment.WalletAuditDTO;
import com.qoder.ride.payment.client.PaymentProClient;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import com.qoder.ride.payment.dataobject.PaymentProAccountDO;
import com.qoder.ride.payment.mapper.LedgerMapper;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import com.qoder.ride.payment.mapper.PaymentProAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 资金监控：定时账面守恒核对（wallet 余额 vs ledger 回放）与 outbox 健康检查，
 * 不平/DEAD/积压超阈值即 error 告警（管理端一致性页同源数据展示）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentMonitor {

    private final PaymentService paymentService;
    private final PaymentProperties properties;
    private final PaymentProIntegration integration;
    private final PaymentProClient client;
    private final PaymentProAccountMapper accountMapper;
    private final MirrorOutboxMapper mirrorMapper;
    private final LedgerMapper ledgerMapper;

    @Scheduled(fixedDelayString = "${payment.audit-interval-ms:30000}")
    public void auditAndAlert() {
        try {
            for (WalletAuditDTO audit : paymentService.audit()) {
                if (!Boolean.TRUE.equals(audit.getOk())) {
                    log.error("账面不平 userId={} walletBalance={} ledgerSum={} diff={}",
                            audit.getUserId(), audit.getWalletBalance(), audit.getLedgerSum(), audit.getDiff());
                }
            }
            OutboxStatsDTO stats = paymentService.outboxStats();
            if (stats.getDeadCount() != null && stats.getDeadCount() > 0) {
                log.error("outbox 存在 DEAD 事件 count={}，需管理端人工重放", stats.getDeadCount());
            }
            if (stats.getOldestPendingAgeMs() != null
                    && stats.getOldestPendingAgeMs() > properties.getOutboxAlertAgeMs()) {
                log.error("outbox 积压超阈值 oldestPendingAgeMs={} threshold={}，订单域可能不可达",
                        stats.getOldestPendingAgeMs(), properties.getOutboxAlertAgeMs());
            }
            if (integration.isEnabled()) {
                mirrorHealth();
                dualLedgerCheck();
            }
        } catch (Exception e) {
            log.warn("定时资金核对异常: {}", e.getMessage());
        }
    }

    private void mirrorHealth() {
        int dead = mirrorMapper.countDead();
        if (dead > 0) {
            log.error("镜像 outbox 存在 DEAD 事件 count={}，需管理端人工重放", dead);
        }
        Long oldest = mirrorMapper.oldestPendingCreatedAt();
        if (oldest != null && System.currentTimeMillis() - oldest > properties.getOutboxAlertAgeMs()) {
            log.error("镜像积压超阈值 oldestAgeMs={} threshold={}，payment-pro 可能不可达",
                    System.currentTimeMillis() - oldest, properties.getOutboxAlertAgeMs());
        }
    }

    /**
     * 双账本核对（增量口径）：payment-pro 余额 应等于 开户基线快照 + 已投递镜像的本地流水净额。
     * 平台（商户账户）另扣除 payment-pro 收取的支付手续费（入 SYS_FEE，属支付系统营收，不在 ride 账本）。
     * 接入前已有余额的钱包/商户账户不受影响；在途镜像跳过避免竞态。
     * 历史映射无快照时按当前状态自校准回填（pp余额 - 已投递净额）。
     */
    private void dualLedgerCheck() {
        Map<String, Long> sentNet = new HashMap<>();
        long ppFees = 0;
        for (MirrorOutboxDO mirror : mirrorMapper.selectSent()) {
            for (LedgerEntryDO leg : ledgerMapper.selectByRequestId(mirror.getRefKey())) {
                sentNet.merge(leg.getUserId(), leg.getAmount(), Long::sum);
            }
            if ("PAY".equals(mirror.getBizType()) && mirror.getPpOrderId() != null) {
                JsonNode payment = client.tradeQuery(mirror.getPpOrderId()).get("payment");
                if (payment != null && payment.hasNonNull("fee")) {
                    ppFees += payment.get("fee").asLong();
                }
            }
        }
        for (PaymentProAccountDO row : accountMapper.selectAll()) {
            boolean platform = properties.getPlatformUserId().equals(row.getUserId());
            if (platform ? mirrorMapper.countPending() > 0 : mirrorMapper.countPendingByUser(row.getUserId()) > 0) {
                continue;
            }
            long ppBalance = client.balance(row.getAccountId());
            long net = sentNet.getOrDefault(row.getUserId(), 0L) - (platform ? ppFees : 0);
            if (row.getPpBalanceSnapshot() == null) {
                accountMapper.updateSnapshot(row.getUserId(), ppBalance - net);
                log.info("双账本基线回填 userId={} snapshot={}", row.getUserId(), ppBalance - net);
                continue;
            }
            long expected = row.getPpBalanceSnapshot() + net;
            if (ppBalance != expected) {
                log.error("双账本不平 userId={} paymentProBalance={} expected={}（基线{}+已镜像净额{}）diff={}",
                        row.getUserId(), ppBalance, expected, row.getPpBalanceSnapshot(), net, ppBalance - expected);
            }
        }
    }
}