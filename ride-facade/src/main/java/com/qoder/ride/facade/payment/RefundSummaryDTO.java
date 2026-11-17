package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 订单累计退款摘要（REFUND_CREDIT 流水聚合），供订单域对账同步。 */
@Data
public class RefundSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** 累计已退金额（分）。 */
    private Long totalAmount;
    /** 退款笔数。 */
    private Integer count;
}