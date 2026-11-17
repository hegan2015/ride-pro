package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProNotifyDO {

    private String event;
    private String bizId;
    private String payload;
    private Long createdAt;
}