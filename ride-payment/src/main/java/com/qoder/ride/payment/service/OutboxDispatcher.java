package com.qoder.ride.payment.service;

import com.qoder.ride.facade.order.OrderFacade;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.PayOutboxDO;
import com.qoder.ride.payment.dataobject.RefundOutboxDO;
import com.qoder.ride.payment.mapper.PayOutboxMapper;
import com.qoder.ride.payment.mapper.RefundOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * outbox 投递：支付/退款成功事件秒级通知订单域幂等完结，失败指数退避（封顶 30s），
 * 重试耗尽置 DEAD 停止自动重试（管理端人工重放）。事件与动账同事务落库，
 * 进程重启后继续投递；订单域对账调度为低频兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final PayOutboxMapper outboxMapper;
    private final RefundOutboxMapper refundOutboxMapper;
    private final PaymentProperties properties;

    @DubboReference
    private OrderFacade orderFacade;

    @Scheduled(fixedDelayString = "${payment.outbox-interval-ms:1000}")
    public void deliver() {
        long now = System.currentTimeMillis();
        for (PayOutboxDO row : outboxMapper.selectPending(now, 20)) {
            try {
                orderFacade.onPaySucceeded(row.getOrderId()).orThrow();
                outboxMapper.markSent(row.getOrderId());
                log.info("outbox 投递成功 orderId={} retries={}", row.getOrderId(), row.getRetries());
            } catch (Exception e) {
                int retries = row.getRetries() + 1;
                if (retries >= properties.getOutboxMaxRetries()) {
                    outboxMapper.markDead(row.getOrderId());
                    log.error("outbox 重试耗尽置 DEAD orderId={} retries={}: {}", row.getOrderId(), retries, e.getMessage());
                } else {
                    outboxMapper.markRetry(row.getOrderId(), now + backoff(retries));
                    log.warn("outbox 投递失败，退避重试 orderId={} retries={}: {}", row.getOrderId(), retries, e.getMessage());
                }
            }
        }
        for (RefundOutboxDO row : refundOutboxMapper.selectPending(now, 20)) {
            try {
                orderFacade.onRefundSucceeded(row.getOrderId()).orThrow();
                refundOutboxMapper.markSent(row.getRequestId());
                log.info("退款 outbox 投递成功 orderId={} requestId={} retries={}",
                        row.getOrderId(), row.getRequestId(), row.getRetries());
            } catch (Exception e) {
                int retries = row.getRetries() + 1;
                if (retries >= properties.getOutboxMaxRetries()) {
                    refundOutboxMapper.markDead(row.getRequestId());
                    log.error("退款 outbox 重试耗尽置 DEAD requestId={} retries={}: {}", row.getRequestId(), retries, e.getMessage());
                } else {
                    refundOutboxMapper.markRetry(row.getRequestId(), now + backoff(retries));
                    log.warn("退款 outbox 投递失败，退避重试 requestId={} retries={}: {}", row.getRequestId(), retries, e.getMessage());
                }
            }
        }
    }

    private long backoff(int retries) {
        return Math.min(30000L, 1000L << Math.min(retries, 5));
    }
}