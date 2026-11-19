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
    /** 多车型同呼：首选+备选列表；首选无应答服务端自动接力备选。为空时用 serviceType。 */
    private java.util.List<String> serviceTypes;
    /** 舒适偏好（逗号分隔：安静/空调/音乐等）。 */
    private String comfort;
    /** 加价调度费（分），完单后划转司机。 */
    private Long tipFee;
}