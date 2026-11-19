package com.qoder.ride.facade.passenger;

import com.qoder.ride.common.result.Result;

import java.util.List;

/** 乘客域：注册与查询。对标滴滴乘客用户系统。 */
public interface PassengerFacade {

    Result<PassengerDTO> create(PassengerCreateRequest request);

    Result<PassengerDTO> get(String passengerId);

    /** 免密自动扣款授权开关。 */
    Result<PassengerDTO> setAutoPay(String passengerId, boolean enabled);

    /** 管理端封禁/解封。 */
    Result<PassengerDTO> setBlocked(String passengerId, boolean blocked);

    Result<List<PassengerDTO>> list();
}