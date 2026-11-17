package com.qoder.ride.payment.dataobject;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProAccountDO {

    private String userId;
    private String memberId;
    private String accountId;
    private Long createdAt;
    private Long ppBalanceSnapshot;
}