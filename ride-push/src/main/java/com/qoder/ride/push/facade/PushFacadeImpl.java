package com.qoder.ride.push.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.facade.push.PushConnectionDTO;
import com.qoder.ride.facade.push.PushEventDTO;
import com.qoder.ride.facade.push.PushFacade;
import com.qoder.ride.push.netty.ConnectionRegistry;
import com.qoder.ride.push.netty.PushAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class PushFacadeImpl implements PushFacade {

    private final ConnectionRegistry registry;
    private final PushAudit audit;

    @Override
    public Result<Void> pushToPassenger(String passengerId, String event, Map<String, Object> data) {
        return FacadeSupport.execute(() -> {
            send(ConnectionRegistry.key("passenger", passengerId), event, data);
            return null;
        });
    }

    @Override
    public Result<Void> pushToDriver(String driverId, String event, Map<String, Object> data) {
        return FacadeSupport.execute(() -> {
            send(ConnectionRegistry.key("driver", driverId), event, data);
            return null;
        });
    }

    @Override
    public Result<Integer> onlineCount() {
        return FacadeSupport.execute(registry::onlineCount);
    }

    @Override
    public Result<List<PushConnectionDTO>> connections() {
        return FacadeSupport.execute(registry::connections);
    }

    @Override
    public Result<List<PushEventDTO>> recentEvents(int limit) {
        return FacadeSupport.execute(() -> audit.recent(limit));
    }

    private void send(String key, String event, Map<String, Object> data) {
        Map<String, Object> body = new HashMap<>();
        body.put("event", event);
        body.put("data", data);
        body.put("timestamp", System.currentTimeMillis());
        boolean sent = registry.send(key, JsonUtil.toJson(body));
        Object orderId = data == null ? null : data.get("orderId");
        audit.record(key, event, orderId == null ? null : String.valueOf(orderId), sent);
        if (sent) {
            log.debug("推送成功 key={} event={}", key, event);
        } else {
            log.debug("客户端离线，跳过推送 key={} event={}", key, event);
        }
    }
}