package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.order.OrderDTO;
import com.qoder.ride.facade.order.OrderFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 行程分享：HMAC 签名链接，无需登录即可查看行程概要 + 轨迹（只读）。 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    @DubboReference
    private OrderFacade orderFacade;
    @DubboReference
    private LocationFacade locationFacade;

    @Value("${gateway.share-secret:ride-share-demo-secret}")
    private String shareSecret;

    @PostMapping("/{orderId}")
    public ResponseEntity<Object> create(@PathVariable String orderId) {
        orderFacade.get(orderId).orThrow();
        String token = sign(orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("token", token);
        data.put("path", "/share.html?orderId=" + orderId + "&token=" + token);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Object> view(@PathVariable String orderId, @RequestParam String token) {
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                sign(orderId).getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body(Map.of("error", "分享链接签名无效", "code", "A0403"));
        }
        OrderDTO order = orderFacade.get(orderId).orThrow();
        order.setPassengerName(mask(order.getPassengerName()));
        order.setDriverName(mask(order.getDriverName()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("track", locationFacade.track(orderId).orThrow());
        return ResponseEntity.ok(data);
    }

    private String sign(String orderId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(shareSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(orderId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private String mask(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return name.charAt(0) + "**";
    }
}