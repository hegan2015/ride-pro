package com.qoder.ride.facade.order;

import com.qoder.ride.facade.pricing.FareDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String passengerId;
    private String passengerName;
    private String driverId;
    private String driverName;
    private String driverCarNo;
    private String originName;
    private Double originLat;
    private Double originLng;
    private String destName;
    private Double destLat;
    private Double destLng;
    private Double distanceKm;
    private Long estimateFee;
    private Long actualFee;
    /** CREATED/DISPATCHING/ACCEPTED/ARRIVED/IN_PROGRESS/COMPLETED/PAID/CANCELLED/EXPIRED/REFUNDED */
    private String status;
    private String requestId;
    private String cancelReason;
    /** 服务类型 EXPRESS/PREMIER/TAXI。 */
    private String serviceType;
    /** 下单时计价快照（费率版本化，账单明细据此展示）。 */
    private FareDTO fare;
    /** 完单实际计价明细（轨迹里程/时长重算，支付确认单据此展示）。 */
    private FareDTO actualFare;
    /** 累计已退款（分）与退款笔数（部分退款）。 */
    private Long refundedAmount;
    private Integer refundCount;
    /** 完单实际里程/时长（轨迹计价）。 */
    private Double actualDistanceKm;
    private Long actualDurationMin;
    /** 派单轮次与已邀约司机（改派/指派排除用）。 */
    private Integer dispatchRound;
    private String offeredDriverIds;
    private String declinedDriverIds;
    /** 乘客取消费（分，0=免费取消）。 */
    private Long cancelFee;
    private Long createdAt;
    private Long acceptedAt;
    private Long arrivedAt;
    private Long startedAt;
    private Long completedAt;
    private Long paidAt;
    private Long cancelledAt;
    private Long expiredAt;
    private Long refundedAt;
}