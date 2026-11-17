package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 通用转账（取消费等）：from 扣款带余额守卫，to 入账，同事务原子 + requestId 幂等。 */
@Data
public class TransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String fromUserId;
    private String toUserId;
    private Long amount;
    private String orderId;
    private String requestId;
    /** 流水类型前缀，落 {bizType}_DEBIT / {bizType}_CREDIT，如 CANCEL_FEE。 */
    private String bizType;
}