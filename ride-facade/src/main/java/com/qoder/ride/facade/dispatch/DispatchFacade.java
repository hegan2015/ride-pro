package com.qoder.ride.facade.dispatch;

import com.qoder.ride.common.result.Result;

/** 派单调度域：就近匹配在线司机并广播抢单邀约。对标滴滴派单引擎。 */
public interface DispatchFacade {

    /** 为一笔订单寻找附近在线司机并推送邀约（抢单模式）。 */
    Result<DispatchResultDTO> dispatch(DispatchRequest request);
}