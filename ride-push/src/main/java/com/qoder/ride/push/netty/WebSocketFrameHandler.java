package com.qoder.ride.push.netty;

import com.qoder.ride.common.util.JsonUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 业务帧处理：握手完成后按 role/id 注册长连接，
 * 处理客户端 PING 心跳，连接断开时注销。无每连接状态，可共享。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    public static final AttributeKey<String> URI_KEY = AttributeKey.valueOf("ws.uri");

    private final ConnectionRegistry registry;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.ServerHandshakeStateEvent
                && evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            String uri = ctx.channel().attr(URI_KEY).get();
            Map<String, String> params = parseIdentity(uri);
            String role = params.get("role");
            String id = params.get("id");
            if (!"passenger".equals(role) && !"driver".equals(role) || id == null || id.isBlank()) {
                log.warn("非法 WebSocket 握手参数 uri={}", uri);
                ctx.close();
                return;
            }
            String key = ConnectionRegistry.key(role, id);
            registry.register(key, ctx.channel());
            Map<String, Object> data = new HashMap<>();
            data.put("role", role);
            data.put("id", id);
            ctx.writeAndFlush(new TextWebSocketFrame(payload("CONNECTED", data)));
        } else if (evt instanceof IdleStateEvent) {
            log.info("WebSocket 空闲超时关闭 key={}", ctx.channel().attr(ConnectionRegistry.CONN_KEY).get());
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        if (text != null && text.contains("PING")) {
            ctx.writeAndFlush(new TextWebSocketFrame(payload("PONG", Map.of())));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        registry.unregister(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("WebSocket 连接异常: {}", cause.getMessage());
        ctx.close();
    }

    private String payload(String event, Map<String, Object> data) {
        Map<String, Object> body = new HashMap<>();
        body.put("event", event);
        body.put("data", data);
        body.put("timestamp", System.currentTimeMillis());
        return JsonUtil.toJson(body);
    }

    /** 身份取自路径 /ws/{role}/{id}（Netty 握手按前缀匹配），兼容查询串 ?role=&id=。 */
    private Map<String, String> parseIdentity(String uri) {
        Map<String, String> params = new HashMap<>();
        if (uri == null) {
            return params;
        }
        try {
            URI parsed = new URI(uri);
            String[] segments = parsed.getPath().split("/");
            if (segments.length >= 4) {
                params.put("role", segments[2]);
                params.put("id", segments[3]);
            }
            String query = parsed.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx > 0) {
                        params.putIfAbsent(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                                URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 WebSocket URI 失败 uri={}", uri);
        }
        return params;
    }
}