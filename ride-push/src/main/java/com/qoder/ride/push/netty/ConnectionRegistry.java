package com.qoder.ride.push.netty;

import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.facade.push.PushConnectionDTO;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长连接注册表：key = role:id（如 passenger:P1 / driver:D1）。
 * 一个用户可能多端登录，这里以最新连接为准（后到覆盖）。
 */
@Slf4j
@Component
public class ConnectionRegistry {

    public static final AttributeKey<String> CONN_KEY = AttributeKey.valueOf("conn.key");

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public static String key(String role, String id) {
        return role + ":" + id;
    }

    public void register(String key, Channel channel) {
        channel.attr(CONN_KEY).set(key);
        Channel prev = channels.put(key, channel);
        if (prev != null && prev != channel && prev.isActive()) {
            // 先告知被顶号方停止重连，再关闭；否则两端自动重连会互相顶号形成活锁。
            String kicked = JsonUtil.toJson(Map.of(
                    "event", "KICKED",
                    "data", Map.of("reason", "同一身份在其他页面登录"),
                    "timestamp", System.currentTimeMillis()));
            prev.writeAndFlush(new TextWebSocketFrame(kicked));
            prev.close();
        }
        log.info("WebSocket 上线 key={} online={}", key, channels.size());
    }

    public void unregister(Channel channel) {
        String key = channel.attr(CONN_KEY).get();
        if (key != null) {
            channels.remove(key, channel);
            log.info("WebSocket 下线 key={} online={}", key, channels.size());
        }
    }

    /** 推送文本帧；客户端离线时返回 false（推送尽力而为）。 */
    public boolean send(String key, String text) {
        Channel channel = channels.get(key);
        if (channel == null || !channel.isActive()) {
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(text));
        return true;
    }

    public int onlineCount() {
        return channels.size();
    }

    public List<PushConnectionDTO> connections() {
        List<PushConnectionDTO> result = new ArrayList<>();
        channels.forEach((key, channel) -> {
            PushConnectionDTO dto = new PushConnectionDTO();
            dto.setKey(key);
            dto.setActive(channel.isActive());
            dto.setRemoteAddress(String.valueOf(channel.remoteAddress()));
            result.add(dto);
        });
        result.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return result;
    }
}