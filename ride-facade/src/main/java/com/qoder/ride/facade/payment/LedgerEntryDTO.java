package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 资金流水条目。amount 带符号（分），balanceAfter 为该笔后钱包余额。 */
@Data
public class LedgerEntryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String userId;
    private String orderId;
    /** TOPUP/PAY_DEBIT/PAY_CREDIT/PLATFORM_FEE/CANCEL_FEE_DEBIT/CANCEL_FEE_CREDIT/WITHDRAW/REFUND_DEBIT/REFUND_FEE_DEBIT/REFUND_CREDIT */
    private String bizType;
    private Long amount;
    private Long balanceAfter;
    private String requestId;
    private Long createdAt;
    /** PENDING/CONFIRMED/FAILED：同步回执流程状态（standalone 恒为 CONFIRMED）。 */
    private String status;
    /** payment-pro 回执号（支付单 ID 或划转 bizNo）。 */
    private String ppReceipt;
}