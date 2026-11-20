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

    public JsonNode tradePay(String ppOrderId, String accountId, String outTradeNo, String callbackUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", ppOrderId);
        params.put("method", "BALANCE");
        params.put("accountId", accountId);
        params.put("outTradeNo", outTradeNo);
        params.put("callbackUrl", callbackUrl);
        return call("trade.pay", params).get("payment");
    }

    public JsonNode tradeQuery(String ppOrderId) {
        return call("trade.query", Map.of("orderId", ppOrderId));
    }

    // ---------------- 收银台开放能力（cashier.*，9 个商户方法） ----------------
    // 复用同一套 HMAC 签名 / HTTPS 信任 / 超时 / 错误语义；入参扁平标量，null 不参与签名也不发送。

    /** 支付方式渲染：orderId 必填，accountId 传入则做账户级可用性判定。 */
    public JsonNode cashierMethods(String orderId, String accountId) {
        return call("cashier.methods", params("orderId", orderId, "accountId", accountId));
    }

    /** 生成付款码（USER_PRESENTED 被扫 / MERCHANT_PRESENTED 主扫）；requestId 同键返回同一有效码。 */
    public JsonNode cashierCodeCreate(String type, String accountId, String orderId, Long amount, String requestId) {
        return call("cashier.code.create", params("type", type, "accountId", accountId,
                "orderId", orderId, "amount", amount, "requestId", requestId));
    }

    /** 解析码（只读，不核销）。 */
    public JsonNode cashierCodeParse(String code) {
        return call("cashier.code.parse", params("code", code));
    }

    /** 查询码状态（ACTIVE/CONSUMED/EXPIRED + ttlSeconds 倒计时）。 */
    public JsonNode cashierCodeQuery(String code) {
        return call("cashier.code.query", params("code", code));
    }

    /** 扫码受理：核销 + 验证 + 幂等支付；主扫必填 payerAccountId。 */
    public JsonNode cashierScanPay(String code, String requestId, String payerAccountId, String method,
                                   String verifyToken, String cardNo, String callbackUrl) {
        return call("cashier.scanPay", params("code", code, "requestId", requestId,
                "payerAccountId", payerAccountId, "method", method, "verifyToken", verifyToken,
                "cardNo", cardNo, "callbackUrl", callbackUrl));
    }

    /** 支付验证四型（PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE），失败不受理支付、不扣款。 */
    public JsonNode cashierVerify(String accountId, String orderId, Long amount, String verifyType,
                                  String payPassword, String smsCode, String bioToken, String requestId) {
        return call("cashier.verify", params("accountId", accountId, "orderId", orderId, "amount", amount,
                "verifyType", verifyType, "payPassword", payPassword, "smsCode", smsCode,
                "bioToken", bioToken, "requestId", requestId));
    }

    /** 验证前置受理（幂等）：BALANCE 同步受理→PROCESSING→SUCCESS，与 trade.pay 语义一致。 */
    public JsonNode cashierPayVerified(String orderId, String requestId, String method, String accountId,
                                       String verifyToken, String cardNo, String callbackUrl) {
        return call("cashier.payVerified", params("orderId", orderId, "requestId", requestId, "method", method,
                "accountId", accountId, "verifyToken", verifyToken, "cardNo", cardNo, "callbackUrl", callbackUrl));
    }

    /** 设置/重置支付口令（6 位数字）；口令只入不出，返回裸 boolean。 */
    public boolean cashierPayPasswordSet(String accountId, String password, String oldPassword) {
        return call("cashier.payPassword.set", params("accountId", accountId,
                "password", password, "oldPassword", oldPassword)).asBoolean();
    }

    /** 发送短信验证码：默认掩码（smsCode=******），仅 sandbox=true 返沙箱明文（联调用）。 */
    public JsonNode cashierSmsSend(String accountId, String orderId, Boolean sandbox) {
        return call("cashier.sms.send", params("accountId", accountId, "orderId", orderId, "sandbox", sandbox));
    }

    /** 扁平参数装配：跳过 null（空串参与签名，故不传空串），保持插入序便于日志排查（签名内部再排序）。 */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return map;
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