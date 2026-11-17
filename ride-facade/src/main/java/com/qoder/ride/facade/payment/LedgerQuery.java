package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 流水查询条件：用户/订单/类型/时间范围 + 分页，全部可选。 */
@Data
public class LedgerQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private String orderId;
    private String bizType;
    private Long from;
    private Long to;
    private Integer page;
    private Integer size;
}