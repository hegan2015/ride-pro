package com.qoder.ride.facade.dispatch;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 派单请求：由订单域携带必要快照下发，避免派单域反向依赖订单详情。 */
@Data
public class DispatchRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String passengerId;
    private String passengerName;
    private String originName;
    private Double originLat;
    private Double originLng;
    private String destName;
    private Double destLat;
    private Double destLng;
    private Long estimateFee;
    private Double distanceKm;
    private Double radiusKm;
    /** grab=就近广播抢单；assign=评分排序逐个指派。 */
    private String mode;
    /** 邀约有效期（毫秒时间戳），司机端倒计时。 */
    private Long offerExpireAt;
    /** 排除司机（逗号分隔）：已拒单/已指派过/司机取消。 */
    private String excludeDriverIds;
    private String serviceType;
}