package com.qoder.ride.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 派单与支付对账调度参数。 */
@Data
@ConfigurationProperties(prefix = "order")
public class OrderProperties {

    private double defaultRadiusKm = 5.0;
    private double maxRadiusKm = 20.0;
    private double radiusStepKm = 3.0;
    private long dispatchTimeoutMs = 15000;
    private long redispatchIntervalMs = 5000;
    private long reconcileIntervalMs = 5000;
    private long reconcileGraceMs = 10000;
    /** grab=广播抢单；assign=评分 Top1 逐个指派。 */
    private String dispatchMode = "grab";
    /** 邀约有效期（司机端倒计时，指派模式即本轮超时）。 */
    private long offerTtlMs = 15000;
    /** 最大派单轮次，超过置 EXPIRED（无人接单终态）。 */
    private int maxDispatchRounds = 6;
    /** 接单后免费取消窗口（毫秒），超窗或司机已到达收取消费。 */
    private long cancelFreeWindowMs = 120000;
    /** 取消费（分），全额归司机。 */
    private long cancelFeeAmount = 300;
    /** BUSY 司机无活跃单回收宽限期（覆盖抢单 CAS 与订单落库间的崩溃窗口）。 */
    private long driverReclaimGraceMs = 30000;
    private long driverReclaimIntervalMs = 10000;
    /** 完单未付催付阈值（每单推送一次 PAYMENT_REMINDER）。 */
    private long remindAfterMs = 60000;
    /** 免密自动扣款：乘客授权后完单宽限期到服务端自动支付。 */
    private long autoPayDelayMs = 30000;
    /** 自动扣款失败（余额不足等）后的重试间隔。 */
    private long autoPayRetryMs = 60000;
}