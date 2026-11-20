package com.qoder.ride.payment.dataobject;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 收银台付款码留痕（pp 签发，ride 侧记录归属订单/用户与最新状态，供轮询与对账）。 */
@Data
@NoArgsConstructor
public class CashierCodeDO {

    private String code;
    private String orderId;
    private String userId;
    private String ppOrderId;
    private String ppAccountId;
    /** USER_PRESENTED / MERCHANT_PRESENTED。 */
    private String type;
    private Long amount;
    /** ACTIVE / CONSUMED / EXPIRED。 */
    private String status;
    private String requestId;
    private Long expireAt;
    private Long createdAt;
    private Long updatedAt;
}