package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 提现单：受理→处理中→成功/失败（模拟渠道异步驱动）。 */
@Data
public class WithdrawOrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String userId;
    private Long amount;
    /** ACCEPTED/PROCESSING/SUCCESS/FAILED */
    private String status;
    private String bizNo;
    private String failReason;
    private Long createdAt;
    private Long updatedAt;
}