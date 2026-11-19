package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ImMessageDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String orderId;
    /** P 乘客 / D 司机。 */
    private String fromRole;
    private String fromId;
    private String text;
    private Long createdAt;
}