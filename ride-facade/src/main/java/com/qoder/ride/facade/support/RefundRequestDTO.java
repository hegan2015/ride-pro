package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class RefundRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String orderId;
    private Long amount;
    private String reason;
    /** PENDING / APPROVED / REJECTED / EXECUTED。 */
    private String status;
    private String requestedBy;
    private String approvedBy;
    private Long createdAt;
}