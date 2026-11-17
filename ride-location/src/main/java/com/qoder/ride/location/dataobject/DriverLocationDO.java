package com.qoder.ride.location.dataobject;

import lombok.Data;

@Data
public class DriverLocationDO {

    private String driverId;
    private Double lat;
    private Double lng;
    private Long updatedAt;
}