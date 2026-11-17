package com.qoder.ride.facade.location;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class NearbyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Double lat;
    private Double lng;
    private Double radiusKm;
    private Integer limit;
}