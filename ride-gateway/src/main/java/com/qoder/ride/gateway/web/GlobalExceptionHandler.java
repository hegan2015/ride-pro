package com.qoder.ride.gateway.web;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> biz(BizException e) {
        return ResponseEntity.status(GatewaySupport.statusOf(e.getCode()))
                .body(Map.of("error", e.getMessage(), "code", e.getCode()));
    }

    @ExceptionHandler(RpcException.class)
    public ResponseEntity<Object> rpc(RpcException e) {
        log.warn("下游 Dubbo 调用失败: {}", e.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of("error", "下游服务不可达: " + e.getMessage(), "code", ResultCode.DOWNSTREAM_ERROR.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400)
                .body(Map.of("error", "请求体不是合法 JSON", "code", ResultCode.PARAM_ERROR.getCode()));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Object> notFound(Exception e) {
        return ResponseEntity.status(404)
                .body(Map.of("error", "资源不存在", "code", ResultCode.NOT_FOUND.getCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unknown(Exception e) {
        log.error("网关未预期异常", e);
        return ResponseEntity.status(500)
                .body(Map.of("error", "系统异常: " + e.getMessage(), "code", ResultCode.SYSTEM_ERROR.getCode()));
    }
}