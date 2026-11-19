package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class InvoiceDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String userId;
    private String orderIds;
    private String title;
    private String taxNo;
    private Long amount;
    /** ISSUED / VOID。 */
    private String status;
    private Long createdAt;
}