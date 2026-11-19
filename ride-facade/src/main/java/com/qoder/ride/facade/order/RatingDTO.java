package com.qoder.ride.facade.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RatingDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    private String role;
    private Integer score;
    private String comment;
    private String tags;
    private Boolean anonymous;
    private Long createdAt;
}