package com.qoder.ride.facade.payment;

import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.common.PageDTO;

import java.util.List;

/** 支付域：乘客/司机钱包与行程扣款。对标滴滴支付/钱包系统。 */
public interface PaymentFacade {

    /** 充值（bizNo 幂等）。userId 为乘客或司机 ID。 */
    Result<WalletDTO> topup(TopupRequest request);

    /** 钱包查询（只读，不存在返回零余额视图）。 */
    Result<WalletDTO> wallet(String userId);

    /** 全部钱包（管理端总览，含平台账户）。 */
    Result<List<WalletDTO>> wallets();

    /** 提现（bizNo 幂等必填）：冻结可用余额并受理提现单，模拟渠道异步驱动成功/失败。 */
    Result<WalletDTO> withdraw(TopupRequest request);

    /** 用户提现单列表（新→旧）。 */
    Result<List<WithdrawOrderDTO>> withdrawOrders(String userId);

    /** 行程支付：扣乘客钱包、入司机钱包（扣平台服务费），requestId 幂等。 */
    Result<PayResultDTO> pay(PayRequest request);

    /** 通用原子转账（取消费等），requestId 幂等，from 余额守卫。 */
    Result<PayResultDTO> transfer(TransferRequest request);

    /** 退款划转（支持部分退款）：校验原单支付流水与累计可退余额，司机/平台退回乘客，
     *  requestId 幂等必填；司机余额不足时平台兜底补足乘客全额；事件与划转同事务落 refund_outbox。 */
    Result<PayResultDTO> refund(RefundRequest request);

    /** 支付结果快照（幂等记录回填），供订单域推送司机实收等明细。 */
    Result<PayResultDTO> payResult(String orderId);

    /** 该订单累计退款摘要（REFUND_CREDIT 求和+笔数），供订单域对账同步。 */
    Result<RefundSummaryDTO> refundSummary(String orderId);

    /** 该订单是否已有提交成功的扣款流水（PAY_DEBIT 与扣款同事务，流水在即钱已动），供订单域对账。 */
    Result<Boolean> isPaid(String orderId);

    /** 该订单是否已有提交成功的退款流水（REFUND_CREDIT），供订单域退款对账。 */
    Result<Boolean> isRefunded(String orderId);

    /** 流水查询（用户/订单/类型/时间范围 + 分页）。 */
    Result<PageDTO<LedgerEntryDTO>> ledger(LedgerQuery query);

    /** 账面核对：ledger 回放求和 vs wallet 余额，逐户返回。 */
    Result<List<WalletAuditDTO>> audit();

    /** 资金统计（按流水类型聚合，含今日）。 */
    Result<List<PaymentStatsDTO>> stats();

    /** outbox 投递监控。 */
    Result<OutboxStatsDTO> outboxStats();

    /** DEAD 事件列表（支付+退款 outbox，重试耗尽待人工介入）。 */
    Result<List<OutboxEntryDTO>> deadOutbox();

    /** 人工重放 DEAD 事件（按 orderId 或 requestId 匹配），返回重置条数。 */
    Result<Integer> replayOutbox(String key);

    /** payment-pro 异步通知留痕（event+bizId 幂等），回调仅加速展示，终态以镜像回执+对账为准。 */
    Result<Void> recordNotify(String event, String bizId, String payload);

    /** 双账本镜像投递监控（未配置集成时全 0）。 */
    Result<OutboxStatsDTO> mirrorStats();

    /** 镜像 DEAD 事件列表（重试耗尽待人工介入）。 */
    Result<List<MirrorEventDTO>> deadMirrors();

    /** 人工重放镜像 DEAD 事件（按 bizNo），返回重置条数。 */
    Result<Integer> replayMirror(String bizNo);

    /** 人工关闭镜像 DEAD 事件（线下处理完毕后销账，不再告警），返回关闭条数。 */
    Result<Integer> closeMirror(String bizNo, String reason);
}