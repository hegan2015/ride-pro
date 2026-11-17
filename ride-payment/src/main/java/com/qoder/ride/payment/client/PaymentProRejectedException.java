package com.qoder.ride.payment.client;

/**
 * payment-pro 明确业务拒绝（HTTP 4xx 且非风控限流）：重试不会改变结果，
 * 本地操作应终态失败（流水 FAILED、冻结退回）。网络/超时/5xx/风控限流不属此类，走恢复重试。
 */
public class PaymentProRejectedException extends RuntimeException {

    public PaymentProRejectedException(String message) {
        super(message);
    }
}