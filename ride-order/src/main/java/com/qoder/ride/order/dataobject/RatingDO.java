package com.qoder.ride.order.dataobject;

import lombok.Data;

@Data
public class RatingDO {

    private String orderId;
    private String role;
    private Integer score;
    private String comment;
    private String tags;
    private Boolean anonymous;
    private Long createdAt;
}