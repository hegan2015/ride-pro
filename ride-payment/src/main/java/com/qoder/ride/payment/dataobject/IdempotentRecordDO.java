package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentRecordDO {

    private String requestId;
    private String bizType;
    private String resultJson;
    private Long createdAt;
}