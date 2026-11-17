package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** payment-pro 双账本镜像事件条目（DEAD 列表/人工重放用）。 */
@Data
public class MirrorEventDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizNo;
    /** TOPUP/PAY/REFUND/WITHDRAW/TRANSFER */
    private String bizType;
    private String refKey;
    private String userId;
    private String orderId;
    private String status;
    private Integer retries;
    private String ppOrderId;
    private String ppPaymentId;
    private String lastError;
    private Long createdAt;
}