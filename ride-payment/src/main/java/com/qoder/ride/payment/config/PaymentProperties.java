package com.qoder.ride.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    /** 平台服务费基点（万分比）。 */
    private int feeBps = 2000;
    /** 平台收入归集账户。 */
    private String platformUserId = "SYS_PLATFORM";
    /** outbox 投递轮询间隔。 */
    private long outboxIntervalMs = 1000;
    /** outbox 重试耗尽阈值，超过置 DEAD 待人工重放。 */
    private int outboxMaxRetries = 10;
    /** outbox 积压告警阈值（最老未投递事件年龄）。 */
    private long outboxAlertAgeMs = 60000;
    /** 账面守恒定时核对间隔。 */
    private long auditIntervalMs = 30000;

    /** 模拟提现渠道：处理时长与失败率（基点，按 bizNo 哈希确定性判定，重试不翻转）。 */
    private long withdrawProcessDelayMs = 2000;
    private int withdrawFailBps = 0;
    private long withdrawProcessIntervalMs = 1000;

    private final Risk risk = new Risk();

    /** 风控限额（分/笔数），动账前校验，超限拒绝。 */
    @Data
    public static class Risk {
        private long topupMaxPerTxn = 1_000_000L;
        private long payMaxPerTxn = 10_000_000L;
        private long transferMaxPerTxn = 1_000_000L;
        private long refundMaxPerTxn = 10_000_000L;
        private long withdrawMaxPerTxn = 500_000L;
        private long withdrawMaxPerDay = 2_000_000L;
        private int withdrawMaxCountPerDay = 10;
    }
}