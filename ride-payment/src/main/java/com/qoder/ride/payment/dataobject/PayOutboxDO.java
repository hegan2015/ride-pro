package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayOutboxDO {

    private String orderId;
    private String requestId;
    private String status;
    private Integer retries;
    private Long nextRetryAt;
    private Long createdAt;
}