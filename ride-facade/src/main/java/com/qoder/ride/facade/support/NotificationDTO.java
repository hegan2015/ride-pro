package com.qoder.ride.facade.support;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class NotificationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private String id;
    private String userId;
    private String type;
    private String title;
    private String body;
    private Boolean readFlag;
    private Long createdAt;
}