package com.qoder.ride.push.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Netty WebSocket 服务器：司乘端经 ws://host:port/ws/{role}/{id} 接入（role=passenger|driver）。
 * 随 Spring 容器启停，独立于 Dubbo 端口对外提供长连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NettyWebSocketServer implements InitializingBean, DisposableBean {

    private final WebSocketFrameHandler frameHandler;

    @Value("${push.websocket-port:9100}")
    private int port;
    @Value("${push.websocket-path:/ws}")
    private String path;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    @Override
    public void afterPropertiesSet() throws Exception {
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("ws-boss", true));
        worker = new NioEventLoopGroup(0, new DefaultThreadFactory("ws-worker", true));
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new UriCaptureHandler());
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler(path, null, true, 65536, false, true));
                        // 90s 无任何读（含客户端 PING 心跳）判定死连接，服务端主动关闭回收。
                        ch.pipeline().addLast(new IdleStateHandler(90, 0, 0));
                        ch.pipeline().addLast(frameHandler);
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Netty WebSocket 服务启动 port={} path={}", port, path);
    }

    @Override
    public void destroy() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
        log.info("Netty WebSocket 服务已停止");
    }

    /** 在 WebSocket 握手消费 HttpRequest 之前捕获 URI，供握手完成后解析 role/id。 */
    private static class UriCaptureHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest request) {
                ctx.channel().attr(WebSocketFrameHandler.URI_KEY).set(request.uri());
            }
            ctx.fireChannelRead(msg);
        }
    }
}