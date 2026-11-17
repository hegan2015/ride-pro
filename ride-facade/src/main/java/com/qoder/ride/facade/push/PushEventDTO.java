package com.qoder.ride.facade.push;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 最近推送事件审计（内存环形缓冲）。 */
@Data
public class PushEventDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String key;
    private String event;
    private String orderId;
    /** 是否送达在线连接（尽力而为，离线跳过）。 */
    private Boolean delivered;
    private Long ts;
}