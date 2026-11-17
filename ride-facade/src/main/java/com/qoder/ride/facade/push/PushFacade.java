package com.qoder.ride.facade.push;

import com.qoder.ride.common.result.Result;

import java.util.List;
import java.util.Map;

/** 消息推送域：经 Netty WebSocket 长连接向司乘端实时推送事件。对标滴滴消息推送。 */
public interface PushFacade {

    Result<Void> pushToPassenger(String passengerId, String event, Map<String, Object> data);

    Result<Void> pushToDriver(String driverId, String event, Map<String, Object> data);

    /** 当前 WebSocket 在线连接数。 */
    Result<Integer> onlineCount();

    /** 当前连接清单（管理端监控）。 */
    Result<List<PushConnectionDTO>> connections();

    /** 最近推送事件审计（内存环形缓冲，最新在前）。 */
    Result<List<PushEventDTO>> recentEvents(int limit);
}