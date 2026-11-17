package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** outbox 事件条目（DEAD 列表/人工重放用）。 */
@Data
public class OutboxEntryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** PAY/REFUND */
    private String type;
    private String orderId;
    private String requestId;
    private String status;
    private Integer retries;
    private Long nextRetryAt;
    private Long createdAt;
}