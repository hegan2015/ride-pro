package com.qoder.ride.common.exception;

import com.qoder.ride.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。携带错误码，由 Facade 层统一捕获转 Result，
 * 或由消费方 orThrow() 在编排链路中触发补偿。
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BizException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }
}