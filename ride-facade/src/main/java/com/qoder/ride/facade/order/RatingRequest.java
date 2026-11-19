package com.qoder.ride.facade.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 行程评价：PAID 后司乘互评，一单一角色一评（幂等）。 */
@Data
public class RatingRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** passenger（评司机）/ driver（评乘客）。 */
    private String role;
    /** 1~5 星。 */
    private Integer score;
    private String comment;
    /** 标签化评价（逗号分隔，如 车内整洁,驾驶平稳）。 */
    private String tags;
    /** 匿名评价。 */
    private Boolean anonymous;
}