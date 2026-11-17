package com.qoder.ride.payment.dataobject;

import lombok.Data;

@Data
public class LedgerEntryDO {

    private String id;
    private String userId;
    private String orderId;
    private String bizType;
    private Long amount;
    private Long balanceAfter;
    private String requestId;
    private Long createdAt;
}