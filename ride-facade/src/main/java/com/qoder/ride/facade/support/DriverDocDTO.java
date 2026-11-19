package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class DriverDocDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String driverId;
    /** DRIVER_LICENSE / TRANSPORT_LICENSE / VEHICLE_LICENSE / INSURANCE。 */
    private String type;
    private String docNo;
    private Long expiry;
    /** PENDING / APPROVED / REJECTED。 */
    private String status;
    private String fileUrl;
    private String reason;
    private Long createdAt;
}