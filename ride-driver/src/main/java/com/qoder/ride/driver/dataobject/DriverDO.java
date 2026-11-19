package com.qoder.ride.driver.dataobject;

import lombok.Data;

@Data
public class DriverDO {

    private String id;
    private String name;
    private String phone;
    private String carNo;
    private String carModel;
    private String status;
    private String serviceType;
    private Double serviceScore;
    private Integer declineCount;
    private Boolean blocked;
    private Long createdAt;
    private Long updatedAt;
}