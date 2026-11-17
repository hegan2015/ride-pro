package com.qoder.ride.facade.driver;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class DriverCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String phone;
    private String carNo;
    private String carModel;
    /** 服务类型 EXPRESS/PREMIER/TAXI，缺省 EXPRESS。 */
    private String serviceType;
}