package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SafetyEventDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String userId;
    private String orderId;
    /** SOS / DEVIATION / RECORDING / SHARE / FATIGUE。 */
    private String type;
    private String detail;
    /** OPEN / HANDLED。 */
    private String status;
    private Long createdAt;
}