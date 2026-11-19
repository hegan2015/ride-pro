package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TicketLinkDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String orderId;
    private String userId;
    private String csTicketId;
    private String category;
    private String status;
    private Long createdAt;
}