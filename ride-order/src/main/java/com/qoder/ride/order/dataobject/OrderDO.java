package com.qoder.ride.order.dataobject;

import lombok.Data;

@Data
public class OrderDO {

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
    private String status;
    private String requestId;
    private String cancelReason;
    private String serviceType;
    private String fareJson;
    private Double actualDistanceKm;
    private Long actualDurationMin;
    private Integer dispatchRound;
    private String offeredDriverIds;
    private String declinedDriverIds;
    private Long cancelFee;
    private Long refundedAmount;
    private Integer refundCount;
    private Long remindedAt;
    private String actualFareJson;
    private Double dispatchRadius;
    private Long lastDispatchAt;
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