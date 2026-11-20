package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收银台受理结果：ride 本地划转结果 + payment-pro 支付单终态快照。
 * cardNo 已脱敏（仅后四位）；repeated=true 表示幂等命中，未重复扣款。
 */
@Data
public class CashierPayDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ride 侧幂等键（PAY-{orderId}），落 idempotent_record + ledger_entry + cashier_request。 */
    private String requestId;
    private String orderId;
    /** payment-pro 交易单 ID。 */
    private String ppOrderId;
    /** payment-pro 支付单 ID（回执号，落 ledger_entry.pp_receipt）。 */
    private String ppPaymentId;
    /** payment-pro 支付单状态：PROCESSING / SUCCESS / FAILED。 */
    private String ppStatus;
    private String method;
    /** 脱敏卡号（**********0202）。 */
    private String cardNo;
    private Long amount;
    private Long fee;
    private Boolean repeated;
    /** ride 本地钱包划转结果（乘客余额/司机实收/平台抽成）。 */
    private PayResultDTO payResult;
}