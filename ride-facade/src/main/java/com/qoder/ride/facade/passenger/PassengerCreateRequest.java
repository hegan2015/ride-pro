package com.qoder.ride.facade.passenger;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PassengerCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String phone;
}