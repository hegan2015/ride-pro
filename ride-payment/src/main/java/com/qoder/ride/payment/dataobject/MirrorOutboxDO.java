package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MirrorOutboxDO {

    private String bizNo;
    private String bizType;
    private String refKey;
    private String userId;
    private String orderId;
    private String status;
    private Integer retries;
    private Long nextRetryAt;
    private String ppOrderId;
    private String ppPaymentId;
    private String lastError;
    private Long createdAt;
}