package com.qoder.ride.common.result;

import com.qoder.ride.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Dubbo 边界统一异常转换（阿里规约：RPC 接口不向调用方抛异常，一律 Result 包装）。
 * 业务异常透传错误码，未知异常归一为系统错误并记录日志。
 */
@Slf4j
public final class FacadeSupport {

    private FacadeSupport() {
    }

    public static <T> Result<T> execute(Supplier<T> action) {
        try {
            return Result.ok(action.get());
        } catch (BizException e) {
            return Result.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Facade 未预期异常", e);
            return Result.fail(ResultCode.SYSTEM_ERROR);
        }
    }
}