package com.qoder.ride.facade.pricing;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class FareRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Double originLat;
    private Double originLng;
    private Double destLat;
    private Double destLng;
    /** 服务类型 EXPRESS/PREMIER/TAXI，缺省 EXPRESS。 */
    private String serviceType;
    /** 实际里程/时长覆盖（完单轨迹计价用）；缺省按起终点直线估算。 */
    private Double distanceKmOverride;
    private Long durationMinOverride;
}