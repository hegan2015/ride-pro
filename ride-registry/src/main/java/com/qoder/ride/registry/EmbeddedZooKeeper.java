package com.qoder.ride.registry;

import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 内嵌 ZooKeeper 生命周期管理，附带 /health HTTP 探针供启动脚本检测。 */
@Slf4j
public class EmbeddedZooKeeper implements InitializingBean, DisposableBean {

    private ZooKeeperServer zkServer;
    private ServerCnxnFactory cnxnFactory;
    private HttpServer healthServer;

    @Override
    public void afterPropertiesSet() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("ZK_PORT", "2181"));
        int healthPort = Integer.parseInt(System.getenv().getOrDefault("ZK_HTTP_PORT", "8092"));
        Path dataDir = Path.of(System.getenv().getOrDefault("DATA_DIR", "data"), "zookeeper");
        Files.createDirectories(dataDir);

        System.setProperty("zookeeper.4lw.commands.whitelist", "ruok,stat,srvr");
        zkServer = new ZooKeeperServer(dataDir.toFile(), dataDir.toFile(), 2000);
        cnxnFactory = ServerCnxnFactory.createFactory();
        cnxnFactory.configure(new InetSocketAddress("127.0.0.1", port), 120);
        cnxnFactory.startup(zkServer);

        healthServer = HttpServer.create(new InetSocketAddress("127.0.0.1", healthPort), 0);
        healthServer.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        healthServer.start();
        log.info("内嵌 ZooKeeper 已启动 port={} dataDir={} health=:{}", port, dataDir, healthPort);
    }

    @Override
    public void destroy() {
        if (healthServer != null) {
            healthServer.stop(0);
        }
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }
        log.info("内嵌 ZooKeeper 已停止");
    }
}