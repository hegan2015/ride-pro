package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 退款划转：司机入账与平台抽成反向退回乘客（REFUND-{orderId}-{seq} 幂等，支持部分退款）。 */
@Data
public class RefundRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String passengerId;
    private String driverId;
    /** 本次退款金额（分），不得超过原支付额减累计已退。 */
    private Long amount;
    /** 本次退还原平台抽成（分），缺省按退款额占原支付额比例折算；司机退回 = amount - fee。 */
    private Long fee;
    private String requestId;
}