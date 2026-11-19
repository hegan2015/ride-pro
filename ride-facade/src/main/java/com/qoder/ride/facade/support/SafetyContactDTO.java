package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class SafetyContactDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String userId;
    private String name;
    private String phone;
    private String relation;
    private Long createdAt;
}