package com.qoder.ride.gateway.web;

import com.qoder.ride.common.result.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Dubbo Result → HTTP 响应适配：错误码映射状态码，错误体兼容前端 {error, code}。 */
public final class GatewaySupport {

    private GatewaySupport() {
    }

    public static <T> ResponseEntity<Object> of(Result<T> result) {
        if (result.isSuccess()) {
            return ResponseEntity.ok(result.getData());
        }
        return ResponseEntity.status(statusOf(result.getCode()))
                .body(Map.of("error", result.getMessage(), "code", result.getCode()));
    }

    public static HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case "A0400" -> HttpStatus.BAD_REQUEST;
            case "A0404" -> HttpStatus.NOT_FOUND;
            case "A0409" -> HttpStatus.CONFLICT;
            case "C0001" -> HttpStatus.BAD_GATEWAY;
            case "B0002" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}