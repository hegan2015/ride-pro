package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class MemberDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String userId;
    private Long points;
    private String level;
    private Long totalSpent;
    private Long updatedAt;
}