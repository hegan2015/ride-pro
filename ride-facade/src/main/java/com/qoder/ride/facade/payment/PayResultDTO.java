package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PayResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long amount;
    private Long fee;
    private Long driverIncome;
    private Long passengerBalance;
    private Boolean repeated;
}