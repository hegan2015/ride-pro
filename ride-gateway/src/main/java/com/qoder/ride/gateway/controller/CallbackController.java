package com.qoder.ride.gateway.controller;

import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.facade.payment.PaymentFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** payment-pro 商户通知回调：幂等留痕，2xx 即送达；资金终态不依赖回调（镜像回执+对账兜底）。 */
@Slf4j
@RestController
@RequestMapping("/api/callbacks")
public class CallbackController {

    @DubboReference
    private PaymentFacade paymentFacade;

    @PostMapping("/payment-pro")
    public ResponseEntity<Object> paymentPro(@RequestBody Map<String, Object> body) {
        String bizId = String.valueOf(body.getOrDefault("refundId", body.getOrDefault("paymentId", "unknown")));
        String event = body.containsKey("refundId") ? "REFUND_SUCCESS" : "PAY_SUCCESS";
        log.info("收到 payment-pro 回调 event={} bizId={}", event, bizId);
        paymentFacade.recordNotify(event, bizId, JsonUtil.toJson(body)).orThrow();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}