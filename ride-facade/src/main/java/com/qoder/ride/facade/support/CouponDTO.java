package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class CouponDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String templateId;
    private String userId;
    private String code;
    /** UNUSED / USED / EXPIRED。 */
    private String status;
    private String orderId;
    private Long expireAt;
    private Long createdAt;
    private String name;
    private String type;
    private Long benefit;
    private Long minAmount;
}