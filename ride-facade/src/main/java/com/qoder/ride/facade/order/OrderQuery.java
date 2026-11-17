package com.qoder.ride.facade.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 订单记录查询条件：乘客/司机/状态/时间范围 + 分页，全部可选。 */
@Data
public class OrderQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String passengerId;
    private String driverId;
    private String status;
    /** created_at 闭区间（毫秒）。 */
    private Long from;
    private Long to;
    private Integer page;
    private Integer size;
}