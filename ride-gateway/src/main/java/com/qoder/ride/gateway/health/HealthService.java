package com.qoder.ride.gateway.health;

import com.qoder.ride.facade.HealthFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 健康聚合：注册中心 HTTP 探针 + 各域 Dubbo ping，供网关与启停脚本就绪检测。 */
@Slf4j
@Service
public class HealthService {

    @DubboReference(group = "ride-passenger", timeout = 1500, check = false)
    private HealthFacade passengerHealth;
    @DubboReference(group = "ride-driver", timeout = 1500, check = false)
    private HealthFacade driverHealth;
    @DubboReference(group = "ride-location", timeout = 1500, check = false)
    private HealthFacade locationHealth;
    @DubboReference(group = "ride-pricing", timeout = 1500, check = false)
    private HealthFacade pricingHealth;
    @DubboReference(group = "ride-order", timeout = 1500, check = false)
    private HealthFacade orderHealth;
    @DubboReference(group = "ride-dispatch", timeout = 1500, check = false)
    private HealthFacade dispatchHealth;
    @DubboReference(group = "ride-payment", timeout = 1500, check = false)
    private HealthFacade paymentHealth;
    @DubboReference(group = "ride-push", timeout = 1500, check = false)
    private HealthFacade pushHealth;
    @DubboReference(group = "ride-support", timeout = 1500, check = false)
    private HealthFacade supportHealth;

    @Value("${registry.health-url}")
    private String registryHealthUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800)).build();

    public Map<String, Object> aggregate() {
        List<Map<String, Object>> services = new ArrayList<>();
        services.add(row("ride-passenger", 20901, ping(passengerHealth)));
        services.add(row("ride-driver", 20902, ping(driverHealth)));
        services.add(row("ride-location", 20903, ping(locationHealth)));
        services.add(row("ride-pricing", 20904, ping(pricingHealth)));
        services.add(row("ride-order", 20905, ping(orderHealth)));
        services.add(row("ride-dispatch", 20906, ping(dispatchHealth)));
        services.add(row("ride-payment", 20907, ping(paymentHealth)));
        services.add(row("ride-push", 20908, ping(pushHealth)));
        services.add(row("ride-support", 20910, ping(supportHealth)));
        boolean registryOk = registryUp();
        boolean allOk = registryOk && services.stream().allMatch(s -> Boolean.TRUE.equals(s.get("ok")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registry", Map.of("ok", registryOk));
        result.put("services", services);
        result.put("allOk", allOk);
        return result;
    }

    private Map<String, Object> row(String service, int port, boolean ok) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("port", port);
        row.put("ok", ok);
        return row;
    }

    private boolean ping(HealthFacade facade) {
        try {
            return facade.ping().isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean registryUp() {
        try {
            // docker 模式注册中心是容器 ZK（无 HTTP 探针），走 4lw ruok TCP 探测。
            if (registryHealthUrl.startsWith("zk://")) {
                URI uri = URI.create(registryHealthUrl);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1500);
                    socket.setSoTimeout(1500);
                    socket.getOutputStream().write("ruok\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    byte[] buf = new byte[16];
                    int n = socket.getInputStream().read(buf);
                    return n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).contains("imok");
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(registryHealthUrl))
                    .timeout(Duration.ofMillis(1500)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}