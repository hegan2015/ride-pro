package com.qoder.ride.common.result;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一 RPC 返回体。Facade 层不抛异常，一律以 Result 表达成功/失败，
 * 避免下游异常穿透 Dubbo 序列化边界。
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.success = true;
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.getCode(), message);
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> r = new Result<>();
        r.success = false;
        r.code = code;
        r.message = message;
        return r;
    }

    /** 取数据，失败时抛 BizException，供消费方在编排链路中快速失败。 */
    public T orThrow() {
        if (!success) {
            throw new com.qoder.ride.common.exception.BizException(code, message);
        }
        return data;
    }
}