package com.qoder.ride.location.dataobject;

import lombok.Data;

@Data
public class TrackPointDO {

    private String orderId;
    private Long seq;
    private Double lat;
    private Double lng;
    private Long ts;
}