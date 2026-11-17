package com.qoder.ride.facade.push;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 当前 WS 连接（管理端监控）。 */
@Data
public class PushConnectionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** role:id，如 driver:D1。 */
    private String key;
    private Boolean active;
    private String remoteAddress;
}