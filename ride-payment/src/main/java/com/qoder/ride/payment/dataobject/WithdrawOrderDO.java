package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawOrderDO {

    private String id;
    private String userId;
    private Long amount;
    private String status;
    private String bizNo;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
}