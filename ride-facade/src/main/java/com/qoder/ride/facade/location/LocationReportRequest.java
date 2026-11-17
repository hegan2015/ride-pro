package com.qoder.ride.facade.location;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class LocationReportRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String driverId;
    private Double lat;
    private Double lng;
    /** 行程中上报携带：轨迹落库 + 实时位置转发乘客。 */
    private String orderId;
    private String passengerId;
}