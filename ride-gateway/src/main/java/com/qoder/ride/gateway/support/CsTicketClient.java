package com.qoder.ride.gateway.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.ride.facade.support.SupportFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 投诉/客服工单转发 cs-pro：优先签名 open 通道（X-Open-Key），未配置时回落内部 /api/tickets。 */
@Slf4j
@Component
public class CsTicketClient {

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @DubboReference
    private SupportFacade supportFacade;

    /** 返回 cs-pro 工单号；cs-pro 不可达时返回 null（本地 linkage 仍受理，离线待追）。 */
    public String createTicket(Map<String, Object> body) {
        Map<String, String> cfg = supportFacade.allConfig().orThrow();
        String base = cfg.getOrDefault("support.csBaseUrl", "http://127.0.0.1:8096");
        String openKey = cfg.getOrDefault("support.csOpenKey", "");
        String path = openKey == null || openKey.isBlank() ? "/api/tickets" : "/api/open/tickets";
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
            if (openKey != null && !openKey.isBlank()) {
                rb.header("X-Open-Key", openKey);
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("cs-pro 工单转发非200 status={} body={}", resp.statusCode(), resp.body());
                return null;
            }
            JsonNode root = om.readTree(resp.body());
            JsonNode data = root.has("data") ? root.get("data") : root;
            for (String field : new String[]{"ticketId", "id", "no", "ticketNo"}) {
                if (data.hasNonNull(field)) {
                    return data.get(field).asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("cs-pro 工单转发失败（离线受理）: {}", e.getMessage());
            return null;
        }
    }
}