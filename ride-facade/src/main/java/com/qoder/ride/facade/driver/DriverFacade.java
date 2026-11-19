package com.qoder.ride.facade.driver;

import com.qoder.ride.common.result.Result;

import java.util.List;

/** 司机域：注册（含车辆）、上下线、服务状态。对标滴滴司机系统。 */
public interface DriverFacade {

    Result<DriverDTO> create(DriverCreateRequest request);

    Result<DriverDTO> get(String driverId);

    Result<List<DriverDTO>> list();

    /** 上线/下线（ONLINE / OFFLINE）。 */
    Result<DriverDTO> setOnline(String driverId, boolean online);

    /** 服务状态流转（ONLINE / BUSY），由订单编排驱动。 */
    Result<DriverDTO> setStatus(String driverId, String status);

    /** 状态 CAS：仅当处于 from 时翻转为 to，返回是否成功（抢单前置 ONLINE→BUSY）。 */
    Result<Boolean> casStatus(String driverId, String from, String to);

    /** 更新服务分（订单域收到乘客评价后驱动）。 */
    Result<Void> updateScore(String driverId, double score);

    /** 拒单计数 +1（派单评分降权）。 */
    Result<Void> recordDecline(String driverId);

    /** 管理端封禁/解封（封禁时强制下线）。 */
    Result<DriverDTO> setBlocked(String driverId, boolean blocked);
}