package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收银台统一入参（对标 payment-pro cashier.* 9 方法，按方法取用所需字段）。
 *
 * 安全口径：payPassword / password / oldPassword / smsCode / bioToken 只在请求方向流动，
 * 服务端不落库、不回显、不入日志；pp appSecret 仅存于 ride 后端配置，永不下发前端。
 * requestId 由 ride 侧确定性生成（PAY-{orderId}），前端不传，重复提交不重复扣款。
 */
@Data
public class CashierRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ride 用户 ID（乘客登录态），服务端据此映射 pp accountId。 */
    private String userId;
    /** ride 订单 ID（行程单）。 */
    private String orderId;
    /** 付款乘客 ID（由网关按订单回填，前端不可指定）。 */
    private String passengerId;
    /** 收款司机 ID（由网关按订单回填）。 */
    private String driverId;
    /** 金额（分），由网关按订单实付回填。 */
    private Long amount;

    /** 支付方式：BALANCE / CARD（BANKCARD 自动归一为 CARD）。 */
    private String method;
    /** 付款码类型：USER_PRESENTED（被扫）/ MERCHANT_PRESENTED（主扫）。 */
    private String type;
    /** 付款码码值（扫码支付入参）。 */
    private String code;

    /** 验证类型：PASSWORD / SMS / BIO / SMALL_AMOUNT_FREE。 */
    private String verifyType;
    /** cashier.verify 签发的一次性令牌（与 payPassword 二选一）。 */
    private String verifyToken;
    /** 内联支付口令（6 位数字，只入不出）。 */
    private String payPassword;
    /** 短信验证码（只入不出）。 */
    private String smsCode;
    /** 指纹/生物令牌（端侧采集，沙箱模拟）。 */
    private String bioToken;

    /** 设置支付口令：新口令。 */
    private String password;
    /** 设置支付口令：旧口令（重置时校验）。 */
    private String oldPassword;

    /** 银行卡号（响应仅回后四位）。 */
    private String cardNo;
    /** 短信沙箱开关：true 返回明文码，仅联调；缺省掩码。 */
    private Boolean sandbox;
}