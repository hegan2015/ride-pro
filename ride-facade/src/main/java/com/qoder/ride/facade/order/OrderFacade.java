package com.qoder.ride.facade.order;

import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.common.PageDTO;

import java.util.List;

/**
 * 订单域：行程订单生命周期编排中枢。
 * CREATED→DISPATCHING→ACCEPTED→ARRIVED→IN_PROGRESS→COMPLETED→PAID，
 * 前期可 CANCELLED，无人接单超轮次 EXPIRED，已支付可 REFUNDED（管理端）。
 * 对标滴滴订单/行程核心。
 */
public interface OrderFacade {

    /** 乘客叫车（requestId 幂等）。 */
    Result<OrderDTO> call(CallRequest request);

    /** 司机接单（抢单 CAS：DISPATCHING→ACCEPTED）。 */
    Result<OrderDTO> accept(String orderId, String driverId);

    /** 司机到达上车点（ACCEPTED→ARRIVED）。 */
    Result<OrderDTO> arrive(String orderId, String driverId);

    /** 开始行程（ARRIVED→IN_PROGRESS）。 */
    Result<OrderDTO> startTrip(String orderId, String driverId);

    /** 结束行程（IN_PROGRESS→COMPLETED），计算最终车费。 */
    Result<OrderDTO> completeTrip(String orderId, String driverId);

    /** 乘客支付（COMPLETED→PAID）。订单维度幂等（PAY-{orderId}），同一订单只扣款一次。 */
    Result<OrderDTO> pay(String orderId);

    /** 支付成功事件回调（支付域 outbox 投递）：COMPLETED 则幂等完结，已 PAID 则无操作。 */
    Result<OrderDTO> onPaySucceeded(String orderId);

    /** 取消订单（行程开始前）。ACCEPTED 超免费窗口或 ARRIVED 后收取消费（CANCEL-{orderId} 幂等）。 */
    Result<OrderDTO> cancel(String orderId, String reason);

    /** 司机取消（ACCEPTED/ARRIVED）：订单回 DISPATCHING 重新派单并排除该司机。 */
    Result<OrderDTO> driverCancel(String orderId, String driverId, String reason);

    /** 司机拒绝邀约：记拒单并排除后续邀约。 */
    Result<Void> declineOffer(String orderId, String driverId);

    /** 行程评价（PAID 后，一单一角色一评）。 */
    Result<RatingDTO> rate(RatingRequest request);

    Result<List<RatingDTO>> ratings(String orderId);

    /** 管理端退款（可部分）：amount 缺省退剩余全额；反向划转（REFUND-{orderId}-{seq} 幂等），
     *  累计退满置 REFUNDED，崩溃窗口由退款 outbox + 对账兜底。 */
    Result<OrderDTO> refund(String orderId, String reason, Long amount);

    /** 退款成功事件回调（支付域 refund_outbox 投递）：按支付域累计退款同步订单状态。 */
    Result<OrderDTO> onRefundSucceeded(String orderId);

    /** 管理端强制取消：行程中异常单关闭，释放司机。 */
    Result<OrderDTO> forceCancel(String orderId, String reason);

    Result<OrderDTO> get(String orderId);

    Result<List<OrderDTO>> list();

    Result<List<OrderDTO>> listByPassenger(String passengerId);

    Result<List<OrderDTO>> listByDriver(String driverId);

    Result<List<OrderDTO>> listByStatus(String status);

    /** 订单记录查询：乘客/司机/状态/时间范围 + 分页。 */
    Result<PageDTO<OrderDTO>> query(OrderQuery query);

    Result<OrderStatsDTO> stats();
}