package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String passengerId;
    private String driverId;
    private Long amount;
    private String requestId;
    /** 资金来源：BALANCE=钱包余额（扣乘客钱包）；CARD=银行卡（外部清算户出资，乘客钱包不动）。 */
    private String method;
}