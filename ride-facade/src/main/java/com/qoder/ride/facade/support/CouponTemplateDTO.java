package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class CouponTemplateDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String name;
    /** FULL_REDUCE 满减（benefit 为减额分）/ DISCOUNT 折扣（benefit 为折扣百分比如 85 表示 85 折）。 */
    private String type;
    private Long benefit;
    private Long minAmount;
    private Integer validDays;
    private Integer total;
    private Integer issued;
    private String status;
    private Long createdAt;
}