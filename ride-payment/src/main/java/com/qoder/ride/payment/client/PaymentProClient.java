package com.qoder.ride.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * payment-pro OpenAPI 客户端：HTTPS（显式信任 payment-pro 自签证书）+ HMAC-SHA256 签名。
 * 配置文件缺失时 enabled=false，ride-pro 保持 standalone，双账本镜像整体关闭。
 */
@Slf4j
@Component
public class PaymentProClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean enabled;
    private String baseUrl;
    private String appId;
    private String appSecret;
    private String merchantAccountId;
    private String merchantMemberId;
    private String callbackBaseUrl;
    private HttpClient http;

    public PaymentProClient(@Value("${payment.pro.config-file:./config/payment-pro.properties}") String configFile) {
        File file = new File(configFile);
        if (!file.isFile()) {
            log.info("payment-pro 集成未配置（{} 不存在），双账本镜像关闭", configFile);
            return;
        }
        try {
            Properties props = new Properties();
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            baseUrl = props.getProperty("base-url");
            appId = props.getProperty("app-id");
            appSecret = props.getProperty("app-secret");
            merchantAccountId = props.getProperty("merchant-account-id");
            merchantMemberId = props.getProperty("merchant-member-id");
            callbackBaseUrl = props.getProperty("callback-base-url", "http://127.0.0.1:8090");
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .sslContext(trustPaymentProCert(props.getProperty("cert-path", "config/payment-pro-cert.pem")))
                    .build();
            enabled = true;
            log.info("payment-pro 集成启用 baseUrl={} appId={} merchantAccount={}", baseUrl, appId, merchantAccountId);
        } catch (Exception e) {
            log.error("payment-pro 集成配置加载失败，双账本镜像关闭: {}", e.getMessage());
        }
    }

    private static SSLContext trustPaymentProCert(String certPath) throws Exception {
        Certificate cert;
        try (FileInputStream in = new FileInputStream(certPath)) {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("payment-pro", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMerchantAccountId() {
        return merchantAccountId;
    }

    public String getMerchantMemberId() {
        return merchantMemberId;
    }

    public String getCallbackBaseUrl() {
        return callbackBaseUrl;
    }

    public JsonNode accountOpen(String name, String type) {
        return call("account.open", Map.of("name", name, "type", type));
    }

    /** 充值资金单（交易单+支付单，SYS_FUND 无手续费），返回支付单 ID 作回执。 */
    public String topup(String accountId, long amount, String bizNo) {
        return call("fund.topup", Map.of("accountId", accountId, "amount", amount, "bizNo", bizNo))
                .get("payment").get("id").asText();
    }

    public void transfer(String fromAccountId, String toAccountId, long amount, String bizNo, String subject) {
        call("account.transfer", Map.of("fromAccountId", fromAccountId, "toAccountId", toAccountId,
                "amount", amount, "bizNo", bizNo, "subject", subject));
    }

    /** 提现资金单（交易单+支付单，SYS_FUND 无手续费），返回支付单 ID 作回执。 */
    public String withdraw(String accountId, long amount, String bizNo) {
        return call("fund.withdraw", Map.of("accountId", accountId, "amount", amount, "bizNo", bizNo))
                .get("payment").get("id").asText();
    }

    public long balance(String accountId) {
        return call("account.balance", Map.of("accountId", accountId)).asLong();
    }

    public String tradeCreate(String subject, long amount, String outTradeNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subject", subject);
        params.put("amount", amount);
        params.put("outTradeNo", outTradeNo);
        return call("trade.create", params).get("id").asText();
    }

    public String tradePay(String ppOrderId, String accountId, String outTradeNo, String callbackUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", ppOrderId);
        params.put("method", "BALANCE");
        params.put("accountId", accountId);
        params.put("outTradeNo", outTradeNo);
        params.put("callbackUrl", callbackUrl);
        return call("trade.pay", params).get("payment").get("id").asText();
    }

    public JsonNode tradeQuery(String ppOrderId) {
        return call("trade.query", Map.of("orderId", ppOrderId));
    }

    private JsonNode call(String endpoint, Map<String, Object> params) {
        if (!enabled) {
            throw new IllegalStateException("payment-pro 集成未配置");
        }
        try {
            Map<String, Object> body = new TreeMap<>(params);
            body.put("appId", appId);
            body.put("timestamp", System.currentTimeMillis());
            body.put("sign", sign(body));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/openapi/" + endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(response.body());
            if (response.statusCode() != 200 || node.has("error")) {
                String error = node.has("error") ? node.get("error").asText() : response.body();
                // 4xx 业务拒绝为终态（风控限流除外，属瞬时可重试）；其余按通信异常走恢复重试
                if (response.statusCode() >= 400 && response.statusCode() < 500
                        && node.has("error") && !error.contains("风控拒绝")) {
                    throw new PaymentProRejectedException("HTTP " + response.statusCode() + " " + response.body());
                }
                throw new RuntimeException("HTTP " + response.statusCode() + " " + response.body());
            }
            return node;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("payment-pro " + endpoint + " 调用异常: " + e.getMessage(), e);
        }
    }

    /** 与 payment-pro OpenApiController.verify 同构：TreeMap 排序、跳过 null/sign、k=v 以 & 连接、HmacSHA256 小写 hex。 */
    private String sign(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(body).entrySet()) {
            if (e.getValue() == null || "sign".equals(e.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }
}