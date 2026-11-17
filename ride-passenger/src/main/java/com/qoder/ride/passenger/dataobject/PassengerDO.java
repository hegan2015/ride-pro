package com.qoder.ride.passenger.dataobject;

import lombok.Data;

@Data
public class PassengerDO {

    private String id;
    private String name;
    private String phone;
    private Boolean autoPay;
    private Long createdAt;
}