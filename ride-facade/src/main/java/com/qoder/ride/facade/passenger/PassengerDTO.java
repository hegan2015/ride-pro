package com.qoder.ride.facade.passenger;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PassengerDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String phone;
    /** 免密自动扣款授权：行程结束宽限期后服务端自动支付。 */
    private Boolean autoPay;
    /** 管理端封禁标记：禁止叫车。 */
    private Boolean blocked;
    private Long createdAt;
}