package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AuditLogDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String admin;
    private String action;
    private String target;
    private String detail;
    private Long createdAt;
}