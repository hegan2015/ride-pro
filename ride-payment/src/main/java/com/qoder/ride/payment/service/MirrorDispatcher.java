package com.qoder.ride.payment.service;

import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.client.PaymentProRejectedException;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 同步回执流程的恢复任务（v1.6.0）：正常路径由请求线程同步调用 payment-pro 并确认流水，
 * 本任务只接管超过宽限期仍未决的 PENDING（请求线程崩溃/通信中断）。
 * 凭确定性幂等键续传：成功→回执确认动账；支付系统明确拒绝→本地终态失败退回；
 * 通信异常退避重试，耗尽置 DEAD 由监控告警、管理端重放或关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MirrorDispatcher {

    private final MirrorOutboxMapper mirrorMapper;
    private final MirrorExecutor mirrorExecutor;
    private final PaymentService paymentService;
    private final PaymentProIntegration integration;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${payment.mirror-interval-ms:1000}")
    public void deliver() {
        if (!integration.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MirrorOutboxDO row : mirrorMapper.selectPending(now, 20)) {
            try {
                String receipt = mirrorExecutor.execute(row);
                paymentService.confirmMirror(row, receipt);
                log.info("PENDING 恢复确认成功 bizNo={} type={} retries={}", row.getBizNo(), row.getBizType(), row.getRetries());
            } catch (PaymentProRejectedException e) {
                paymentService.failMirror(row, e.getMessage());
                log.warn("PENDING 恢复：支付系统拒绝，操作终态失败 bizNo={}: {}", row.getBizNo(), e.getMessage());
            } catch (Exception e) {
                int retries = row.getRetries() + 1;
                String error = truncate(e.getMessage());
                if (retries >= properties.getOutboxMaxRetries()) {
                    mirrorMapper.markDead(row.getBizNo(), error);
                    log.error("PENDING 恢复重试耗尽置 DEAD bizNo={} retries={}: {}", row.getBizNo(), retries, error);
                } else {
                    mirrorMapper.markRetry(row.getBizNo(), now + backoff(retries), error);
                    log.warn("PENDING 恢复失败退避重试 bizNo={} retries={}: {}", row.getBizNo(), retries, error);
                }
            }
        }
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