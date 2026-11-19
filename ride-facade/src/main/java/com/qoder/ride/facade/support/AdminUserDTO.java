package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AdminUserDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String username;
    private String passwordHash;
    /** ADMIN / OPS / FINANCE / SUPPORT。 */
    private String role;
    private String status;
    private Long createdAt;
}