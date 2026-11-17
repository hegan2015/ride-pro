package com.qoder.ride.facade.pricing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** 当前计价规则表（管理端展示/乘客端费率说明）。金额单位：分。 */
@Data
public class PricingRulesDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String version;
    private Map<String, ServiceRate> services;
    private List<PeakWindow> peakWindows;
    private Double longDistanceThresholdKm;
    private Long longDistancePerKmFee;

    @Data
    public static class ServiceRate implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String label;
        private Long baseFee;
        private Double baseKm;
        private Long perKmFee;
        private Long perMinFee;
        private Long minFare;
    }

    @Data
    public static class PeakWindow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String label;
        /** 小时区间 [startHour, endHour)，跨午夜时 start>end。 */
        private Integer startHour;
        private Integer endHour;
        private Double rate;
    }
}