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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 通道费进程内缓存（支付单终态后 fee 不变）。pp 热点限流 50 QPS/appId，故每轮限 20 条且间隔 60ms。 */
    private final Map<String, Long> feeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FEE_FETCH_PER_CYCLE = 20;
    private static final long FEE_FETCH_GAP_MS = 60L;

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
    /**
     * 逐户已确认净额（仅 CONFIRMED，与钱包动账一一对应）+ pp 收取的通道手续费合计（平台户口径补偿用）。
     * 用全量 CONFIRMED 腿而非「已投递镜像的腿」：历史冲正是本地更正不镜像 pp，
     * 只统计镜像腿会让冲正对监控不可见，冲正后仍误报不平。PENDING/FAILED 天然被排除。
     *
     * <p>通道费按镜像 bizNo 进程内缓存 + 每轮限额补取：支付单终态后 fee 不再变，
     * 而逐条远程查会在每轮监控打出 N 次 trade.query 直接撞 pp 限流（A0429）。
     * 缓存未补全时 feesComplete=false，平台户核对与基线重算据此跳过，避免用低估的手续费误报。
     */
    private NetSnapshot sentNetSnapshot() {
        Map<String, Long> sentNet = new HashMap<>();
        for (Map<String, Object> row : ledgerMapper.sumByUser()) {
            sentNet.put(String.valueOf(row.get("user_id")), ((Number) row.get("ledger_sum")).longValue());
        }
        long ppFees = 0;
        boolean complete = true;
        int fetched = 0;
        for (MirrorOutboxDO mirror : mirrorMapper.selectSent()) {
            if (!"PAY".equals(mirror.getBizType()) || mirror.getPpOrderId() == null) {
                continue;
            }
            Long fee = feeCache.get(mirror.getBizNo());
            if (fee == null) {
                if (fetched >= FEE_FETCH_PER_CYCLE) {
                    complete = false;
                    continue;
                }
                try {
                    if (fetched > 0) {
                        Thread.sleep(FEE_FETCH_GAP_MS);
                    }
                    JsonNode payment = client.tradeQuery(mirror.getPpOrderId()).get("payment");
                    fee = payment != null && payment.hasNonNull("fee") ? payment.get("fee").asLong() : 0L;
                    feeCache.put(mirror.getBizNo(), fee);
                    fetched++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    complete = false;
                    break;
                } catch (RuntimeException e) {
                    // 限流/不可达：本轮放弃补取，下轮继续，绝不用部分手续费冒充全量
                    log.warn("通道费补取中断（下轮续补）bizNo={}: {}", mirror.getBizNo(), e.getMessage());
                    complete = false;
                    break;
                }
            }
            ppFees += fee;
        }
        return new NetSnapshot(sentNet, ppFees, complete);
    }

    private record NetSnapshot(Map<String, Long> sentNet, long ppFees, boolean feesComplete) {
        long net(String userId, boolean platform) {
            return sentNet.getOrDefault(userId, 0L) - (platform ? ppFees : 0);
        }
    }

    /**
     * 基线重算（历史污染修复）：仅在镜像全局静默时允许，把快照重设为「当前 pp 余额 − 已投递净额」。
     * 污染成因是旧版按镜像行 userId（只含乘客）判在途，司机/平台基线在净额低估时被回填。
     * 重算前必须先完成资金冲正，否则会把真实缺口一并吸收进基线（掩盖不平）。
     */
    public Map<String, Object> rebaseline() {
        int pending = mirrorMapper.countPending();
        if (pending > 0) {
            throw new IllegalStateException("镜像未静默（pending=" + pending + "），禁止重算基线");
        }
        NetSnapshot snap = sentNetSnapshot();
        if (!snap.feesComplete()) {
            throw new IllegalStateException("通道费缓存尚未补全（pp 限流节流中），禁止重算基线，请稍后重试");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PaymentProAccountDO row : accountMapper.selectAll()) {
            long ppBalance = client.balance(row.getAccountId());
            boolean platform = properties.getPlatformUserId().equals(row.getUserId());
            long net = snap.net(row.getUserId(), platform);
            long updated = ppBalance - net;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", row.getUserId());
            item.put("accountId", row.getAccountId());
            item.put("ppBalance", ppBalance);
            item.put("mirroredNet", net);
            item.put("oldSnapshot", row.getPpBalanceSnapshot());
            item.put("newSnapshot", updated);
            rows.add(item);
            accountMapper.updateSnapshot(row.getUserId(), updated);
            log.warn("双账本基线重算 userId={} old={} new={}（pp余额{} − 已镜像净额{}）",
                    row.getUserId(), row.getPpBalanceSnapshot(), updated, ppBalance, net);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quiet", true);
        out.put("feesComplete", snap.feesComplete());
        out.put("ppFees", snap.ppFees());
        out.put("accounts", rows);
        return out;
    }

    private void dualLedgerCheck() {
        NetSnapshot snap = sentNetSnapshot();
        // 在途镜像按「全部借贷腿的用户」判定，而非镜像行的 userId（那只是乘客）：
        // 一笔 PAY 同时动司机与平台的钱，只按乘客判在途会让司机/平台基线在净额低估时被回填，
        // 快照吸收在途金额后永久重复计算。回填基线 additionally 要求全局静默。
        Set<String> inFlight = new HashSet<>();
        for (MirrorOutboxDO mirror : mirrorMapper.selectPending(System.currentTimeMillis(), 1000)) {
            for (LedgerEntryDO leg : ledgerMapper.selectByRequestId(mirror.getRefKey())) {
                inFlight.add(leg.getUserId());
            }
        }
        boolean quiet = mirrorMapper.countPending() == 0;
        for (PaymentProAccountDO row : accountMapper.selectAll()) {
            if (inFlight.contains(row.getUserId())) {
                continue;
            }
            long ppBalance = client.balance(row.getAccountId());
            boolean platform = properties.getPlatformUserId().equals(row.getUserId());
            long net = snap.net(row.getUserId(), platform);
            if (platform && !snap.feesComplete()) {
                continue;
            }
            if (row.getPpBalanceSnapshot() == null) {
                if (!quiet) {
                    continue;
                }
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