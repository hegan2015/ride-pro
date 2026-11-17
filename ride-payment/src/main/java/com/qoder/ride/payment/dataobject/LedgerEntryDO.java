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
    /** PENDING（已落流水待支付系统回执）/ CONFIRMED（回执确认已动账）/ FAILED（支付系统拒绝）。 */
    private String status;
    private String ppReceipt;
}