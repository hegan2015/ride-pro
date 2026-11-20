package com.qoder.ride.payment.dataobject;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收银台受理留痕（幂等 requestId = PAY-{orderId}，与订单域支付键一致）。
 *
 * 存在此记录即表示该笔行程支付走 cashier.* 受理（而非 trade.pay），
 * MirrorExecutor 据此选择收银台分支；同步请求与崩溃恢复共用同一 requestId，pp 侧幂等不重复扣款。
 * 安全：只存一次性 verifyToken 与脱敏卡号，绝不存支付口令/短信码/appSecret。
 */
@Data
@NoArgsConstructor
public class CashierRequestDO {

    private String requestId;
    private String orderId;
    private String userId;
    private String ppOrderId;
    private String ppAccountId;
    private Long amount;
    /** VERIFIED（cashier.payVerified）/ SCAN（cashier.scanPay）。 */
    private String mode;
    private String method;
    /** 扫码受理的码值（mode=SCAN）。 */
    private String code;
    /** 脱敏卡号（仅后四位）。 */
    private String cardNo;
    private String verifyType;
    private String verifyToken;
    /** NEW / SENT / FAILED。 */
    private String status;
    private String ppPaymentId;
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
}