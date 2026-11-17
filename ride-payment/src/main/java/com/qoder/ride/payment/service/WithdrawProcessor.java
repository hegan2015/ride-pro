package com.qoder.ride.payment.service;

import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.WithdrawOrderDO;
import com.qoder.ride.payment.mapper.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 模拟提现渠道：受理单转处理中，停留超过渠道时长后驱动终态（成功扣款/失败解冻）。
 * 崩溃后 PROCESSING 卡单由同一扫描重驱动，终态 CAS 与动账同事务保证只完成一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawProcessor {

    private final WithdrawOrderMapper withdrawOrderMapper;
    private final PaymentService paymentService;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${payment.withdraw-process-interval-ms:1000}")
    public void process() {
        long now = System.currentTimeMillis();
        for (WithdrawOrderDO row : withdrawOrderMapper.selectByStatus("ACCEPTED", 20)) {
            withdrawOrderMapper.casStatus(row.getId(), "ACCEPTED", "PROCESSING", now);
        }
        for (WithdrawOrderDO row : withdrawOrderMapper.selectProcessable(
                now - properties.getWithdrawProcessDelayMs(), 20)) {
            try {
                paymentService.settleWithdraw(row);
            } catch (Exception e) {
                log.warn("提现终态驱动失败，下轮重试 withdrawId={}: {}", row.getId(), e.getMessage());
            }
        }
    }
}