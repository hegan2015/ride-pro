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
}