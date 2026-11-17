package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 资金统计（按流水类型聚合）。金额单位：分。 */
@Data
public class PaymentStatsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String bizType;
    private Long totalAmount;
    private Long entryCount;
    private Long todayAmount;
}