package com.qoder.ride.facade.location;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class DriverLocationDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String driverId;
    private Double lat;
    private Double lng;
    /** 就近查询时填充：与查询中心点的距离（公里）。 */
    private Double distanceKm;
    private Long updatedAt;
}