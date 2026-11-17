package com.qoder.ride.facade.pricing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 计价明细。金额单位：分；distanceKm/durationMin 为度量值。 */
@Data
public class FareDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Double distanceKm;
    private Long durationMin;
    private Long baseFee;
    private Long distanceFee;
    private Long timeFee;
    /** 远途费（超远途里程部分）。 */
    private Long longDistanceFee;
    /** 高峰/夜间时段系数（1.0=平峰）。 */
    private Double peakRate;
    /** 计价规则版本（下单快照，完单沿用）。 */
    private String version;
    private String serviceType;
    private Long totalFee;
}