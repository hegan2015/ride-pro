package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TopupRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private Long amount;
    private String bizNo;
}