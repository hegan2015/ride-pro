package com.qoder.ride.facade.driver;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class DriverDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String phone;
    private String carNo;
    private String carModel;
    /** OFFLINE / ONLINE / BUSY */
    private String status;
    /** EXPRESS/PREMIER/TAXI。 */
    private String serviceType;
    /** 服务分（乘客评价滚动均分，初始 5.0）。 */
    private Double serviceScore;
    private Integer declineCount;
    /** 管理端封禁标记：禁止出车/接单。 */
    private Boolean blocked;
    private Long createdAt;
    /** 最近状态变更时刻（BUSY 司机回收宽限判定）。 */
    private Long updatedAt;
}