package com.qoder.ride.common.result;

/**
 * 统一错误码。遵循阿里错误码规约：五位字符串，
 * A 开头为用户端错误，B 开头为当前系统错误，C 开头为第三方服务错误。
 */
public enum ResultCode {

    SUCCESS("00000", "成功"),
    PARAM_ERROR("A0400", "请求参数错误"),
    NOT_FOUND("A0404", "资源不存在"),
    CONFLICT("A0409", "当前状态不允许该操作"),
    BIZ_ERROR("B0001", "业务处理失败"),
    SYSTEM_ERROR("B0002", "系统内部错误"),
    DOWNSTREAM_ERROR("C0001", "下游服务异常");

    private final String code;
    private final String message;

    ResultCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}