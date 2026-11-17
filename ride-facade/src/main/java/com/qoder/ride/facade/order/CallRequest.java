package com.qoder.ride.facade.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class CallRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String passengerId;
    private String originName;
    private Double originLat;
    private Double originLng;
    private String destName;
    private Double destLat;
    private Double destLng;
    /** 幂等键。 */
    private String requestId;
    /** 服务类型 EXPRESS/PREMIER/TAXI，缺省 EXPRESS。 */
    private String serviceType;
}