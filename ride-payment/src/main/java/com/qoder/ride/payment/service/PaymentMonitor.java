package com.qoder.ride.payment.service;

import com.qoder.ride.facade.payment.OutboxStatsDTO;
import com.qoder.ride.facade.payment.WalletAuditDTO;
import com.qoder.ride.payment.config.PaymentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        } catch (Exception e) {
            log.warn("定时资金核对异常: {}", e.getMessage());
        }
    }
}