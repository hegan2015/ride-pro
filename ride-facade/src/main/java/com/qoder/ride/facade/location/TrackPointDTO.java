package com.qoder.ride.facade.location;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 行程轨迹点（按 seq 升序）。 */
@Data
public class TrackPointDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long seq;
    private Double lat;
    private Double lng;
    private Long ts;
}