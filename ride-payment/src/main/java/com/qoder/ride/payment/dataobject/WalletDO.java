package com.qoder.ride.payment.dataobject;

import lombok.Data;

@Data
public class WalletDO {

    private String userId;
    private Long balance;
    private Long frozenAmount;
    private Long updatedAt;
}