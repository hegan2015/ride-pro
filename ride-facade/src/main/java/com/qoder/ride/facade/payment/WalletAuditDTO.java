package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 账面核对结果：ledger 逐笔回放求和 vs wallet 余额。 */
@Data
public class WalletAuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private Long walletBalance;
    private Long ledgerSum;
    private Long diff;
    private Boolean ok;
    private Long entryCount;
}