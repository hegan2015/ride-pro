package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** outbox 投递监控。 */
@Data
public class OutboxStatsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long pendingCount;
    private Long sentCount;
    /** 重试耗尽置 DEAD 的事件数（支付+退款），需人工重放。 */
    private Long deadCount;
    private Integer maxRetries;
    /** 最老未投递事件年龄（毫秒），无积压为 0。 */
    private Long oldestPendingAgeMs;
}