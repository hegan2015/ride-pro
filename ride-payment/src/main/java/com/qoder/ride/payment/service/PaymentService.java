package com.qoder.ride.payment.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.common.util.MoneyUtil;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.payment.LedgerEntryDTO;
import com.qoder.ride.facade.payment.LedgerQuery;
import com.qoder.ride.facade.payment.OutboxEntryDTO;
import com.qoder.ride.facade.payment.OutboxStatsDTO;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.facade.payment.PaymentStatsDTO;
import com.qoder.ride.facade.payment.RefundRequest;
import com.qoder.ride.facade.payment.RefundSummaryDTO;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.facade.payment.TransferRequest;
import com.qoder.ride.facade.payment.WalletAuditDTO;
import com.qoder.ride.facade.payment.WalletDTO;
import com.qoder.ride.facade.payment.WithdrawOrderDTO;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.IdempotentRecordDO;
import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import com.qoder.ride.payment.dataobject.PayOutboxDO;
import com.qoder.ride.payment.dataobject.RefundOutboxDO;
import com.qoder.ride.payment.dataobject.WalletDO;
import com.qoder.ride.payment.dataobject.WithdrawOrderDO;
import com.qoder.ride.payment.mapper.IdempotentMapper;
import com.qoder.ride.payment.mapper.LedgerMapper;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import com.qoder.ride.payment.mapper.PayOutboxMapper;
import com.qoder.ride.payment.mapper.RefundOutboxMapper;
import com.qoder.ride.payment.mapper.WalletMapper;
import com.qoder.ride.payment.mapper.WithdrawOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包与资金划转。所有动账（支付/转账/退款/充值/提现）在同一本地事务内原子完成，
 * 扣款带可用余额守卫防透支，幂等键必填防重复入账，每笔落流水可审计回放；
 * 退款校验原单支付流水与累计可退余额，提现走冻结→模拟渠道异步终态的状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final WalletMapper walletMapper;
    private final LedgerMapper ledgerMapper;
    private final IdempotentMapper idempotentMapper;
    private final PayOutboxMapper outboxMapper;
    private final RefundOutboxMapper refundOutboxMapper;
    private final WithdrawOrderMapper withdrawOrderMapper;
    private final TransactionTemplate transactionTemplate;
    private final PaymentProperties properties;
    private final MirrorOutboxMapper mirrorOutboxMapper;
    private final PaymentProIntegration integration;

    /** 双账本镜像事件：与本地动账同事务写入；未配置 payment-pro 集成时不落事件，standalone 行为不变。 */
    private void mirror(String bizType, String refKey, String userId, String orderId, long now) {
        if (!integration.isEnabled()) {
            return;
        }
        mirrorOutboxMapper.insert(new MirrorOutboxDO("M-" + bizType + "-" + refKey, bizType, refKey, userId, orderId,
                "NEW", 0, 0L, null, null, null, now));
    }

    public WalletDTO topup(TopupRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户 ID 必填");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0 : request.getAmount());
        String bizNo = requireKey(request.getBizNo(), "bizNo");
        checkLimit(amount, properties.getRisk().getTopupMaxPerTxn(), "充值");

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(bizNo);
        if (existing != null && existing.getResultJson() != null) {
            log.info("充值幂等命中 bizNo={}", bizNo);
            return JsonUtil.fromJson(existing.getResultJson(), WalletDTO.class);
        }
        long now = System.currentTimeMillis();
        try {
            WalletDTO dto = transactionTemplate.execute(s -> {
                walletMapper.ensure(request.getUserId(), now);
                walletMapper.credit(request.getUserId(), amount, now);
                recordLedger(request.getUserId(), null, "TOPUP", amount, bizNo, now);
                WalletDTO wallet = toWallet(walletMapper.selectByUserId(request.getUserId()));
                idempotentMapper.insert(new IdempotentRecordDO(bizNo, "TOPUP", JsonUtil.toJson(wallet), now));
                mirror("TOPUP", bizNo, request.getUserId(), null, now);
                return wallet;
            });
            log.info("钱包充值 userId={} amount={} balance={}", request.getUserId(), amount, dto.getBalance());
            return dto;
        } catch (DuplicateKeyException e) {
            return repeatedWallet(bizNo);
        }
    }

    /** 只读查询：钱包不存在返回零余额视图，不产生写副作用。 */
    public WalletDTO wallet(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户 ID 必填");
        }
        WalletDO wallet = walletMapper.selectByUserId(userId);
        if (wallet == null) {
            WalletDTO dto = new WalletDTO();
            dto.setUserId(userId);
            dto.setBalance(0L);
            return dto;
        }
        return toWallet(wallet);
    }

    public List<WalletDTO> wallets() {
        return walletMapper.selectAll().stream().map(this::toWallet).toList();
    }

    /** 提现受理：风控校验→冻结可用余额→落提现单（ACCEPTED），模拟渠道异步驱动成功/失败终态。 */
    public WalletDTO withdraw(TopupRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户 ID 必填");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0 : request.getAmount());
        String bizNo = requireKey(request.getBizNo(), "bizNo");
        PaymentProperties.Risk risk = properties.getRisk();
        checkLimit(amount, risk.getWithdrawMaxPerTxn(), "提现");
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long todayAmount = withdrawOrderMapper.sumActiveSince(request.getUserId(), todayStart);
        if (todayAmount + amount > risk.getWithdrawMaxPerDay()) {
            throw new BizException(ResultCode.CONFLICT,
                    "超出当日提现累计限额: 已提=" + todayAmount + " 限额=" + risk.getWithdrawMaxPerDay());
        }
        if (withdrawOrderMapper.countSince(request.getUserId(), todayStart) >= risk.getWithdrawMaxCountPerDay()) {
            throw new BizException(ResultCode.CONFLICT, "超出当日提现次数限制: " + risk.getWithdrawMaxCountPerDay());
        }

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(bizNo);
        if (existing != null && existing.getResultJson() != null) {
            log.info("提现幂等命中 bizNo={}", bizNo);
            return JsonUtil.fromJson(existing.getResultJson(), WalletDTO.class);
        }
        long now = System.currentTimeMillis();
        try {
            WalletDTO dto = transactionTemplate.execute(s -> {
                walletMapper.ensure(request.getUserId(), now);
                if (walletMapper.freeze(request.getUserId(), amount, now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "可用余额不足，无法提现");
                }
                withdrawOrderMapper.insert(new WithdrawOrderDO("W" + withdrawOrderMapper.nextId(),
                        request.getUserId(), amount, "ACCEPTED", bizNo, null, now, now));
                WalletDTO wallet = toWallet(walletMapper.selectByUserId(request.getUserId()));
                idempotentMapper.insert(new IdempotentRecordDO(bizNo, "WITHDRAW", JsonUtil.toJson(wallet), now));
                return wallet;
            });
            log.info("提现受理 userId={} amount={} balance={} frozen={}",
                    request.getUserId(), amount, dto.getBalance(), dto.getFrozenAmount());
            return dto;
        } catch (DuplicateKeyException e) {
            return repeatedWallet(bizNo);
        }
    }

    /**
     * 提现终态（模拟渠道回调，WithdrawProcessor 调度）：成功=扣款+解冻+WITHDRAW 流水，
     * 失败=仅解冻；单据 CAS 与动账同事务，崩溃后重驱动幂等。失败率按 bizNo 哈希确定性判定，重试不翻转。
     */
    public void settleWithdraw(WithdrawOrderDO row) {
        long now = System.currentTimeMillis();
        boolean fail = Math.floorMod(row.getBizNo().hashCode(), 10000) < properties.getWithdrawFailBps();
        if (fail) {
            transactionTemplate.executeWithoutResult(s -> {
                if (withdrawOrderMapper.fail(row.getId(), "模拟渠道拒绝", now) == 0) {
                    return;
                }
                walletMapper.unfreeze(row.getUserId(), row.getAmount(), now);
                idempotentMapper.merge(new IdempotentRecordDO(row.getBizNo(), "WITHDRAW",
                        JsonUtil.toJson(toWallet(walletMapper.selectByUserId(row.getUserId()))), now));
            });
            log.warn("提现失败已解冻 withdrawId={} userId={} amount={}", row.getId(), row.getUserId(), row.getAmount());
        } else {
            transactionTemplate.executeWithoutResult(s -> {
                if (withdrawOrderMapper.casStatus(row.getId(), "PROCESSING", "SUCCESS", now) == 0) {
                    return;
                }
                if (walletMapper.debitAndUnfreeze(row.getUserId(), row.getAmount(), now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "提现扣款失败（冻结不足）: " + row.getId());
                }
                recordLedger(row.getUserId(), null, "WITHDRAW", -row.getAmount(), row.getBizNo(), now);
                mirror("WITHDRAW", row.getBizNo(), row.getUserId(), null, now);
                idempotentMapper.merge(new IdempotentRecordDO(row.getBizNo(), "WITHDRAW",
                        JsonUtil.toJson(toWallet(walletMapper.selectByUserId(row.getUserId()))), now));
            });
            log.info("提现成功 withdrawId={} userId={} amount={}", row.getId(), row.getUserId(), row.getAmount());
        }
    }

    public List<WithdrawOrderDTO> withdrawOrders(String userId) {
        return withdrawOrderMapper.selectByUser(userId).stream().map(this::toWithdrawDTO).toList();
    }

    public PayResultDTO pay(PayRequest request) {
        if (request.getPassengerId() == null || request.getDriverId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "乘客/司机 ID 必填");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0 : request.getAmount());
        String requestId = requireKey(request.getRequestId(), "requestId");
        checkLimit(amount, properties.getRisk().getPayMaxPerTxn(), "支付");

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(requestId);
        if (existing != null && existing.getResultJson() != null) {
            log.info("支付幂等命中 requestId={}", requestId);
            PayResultDTO repeated = JsonUtil.fromJson(existing.getResultJson(), PayResultDTO.class);
            repeated.setRepeated(true);
            return repeated;
        }

        long fee = MoneyUtil.fee(amount, properties.getFeeBps());
        long driverIncome = amount - fee;
        String platform = properties.getPlatformUserId();
        long now = System.currentTimeMillis();
        try {
            PayResultDTO result = transactionTemplate.execute(s -> {
                walletMapper.ensure(request.getPassengerId(), now);
                if (walletMapper.debit(request.getPassengerId(), amount, now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "乘客余额不足，请先充值");
                }
                recordLedger(request.getPassengerId(), request.getOrderId(), "PAY_DEBIT", -amount, requestId, now);

                walletMapper.ensure(request.getDriverId(), now);
                walletMapper.credit(request.getDriverId(), driverIncome, now);
                recordLedger(request.getDriverId(), request.getOrderId(), "PAY_CREDIT", driverIncome, requestId, now);

                walletMapper.ensure(platform, now);
                walletMapper.credit(platform, fee, now);
                recordLedger(platform, request.getOrderId(), "PLATFORM_FEE", fee, requestId, now);

                long passengerBalance = walletMapper.selectByUserId(request.getPassengerId()).getBalance();
                PayResultDTO dto = new PayResultDTO();
                dto.setOrderId(request.getOrderId());
                dto.setAmount(amount);
                dto.setFee(fee);
                dto.setDriverIncome(driverIncome);
                dto.setPassengerBalance(passengerBalance);
                dto.setRepeated(false);
                idempotentMapper.insert(new IdempotentRecordDO(requestId, "PAY", JsonUtil.toJson(dto), now));
                outboxMapper.insert(new PayOutboxDO(request.getOrderId(), requestId, "NEW", 0, 0L, now));
                mirror("PAY", requestId, request.getPassengerId(), request.getOrderId(), now);
                return dto;
            });
            log.info("行程支付 orderId={} amount={} fee={} driverIncome={} passengerBalance={}",
                    request.getOrderId(), amount, fee, driverIncome, result.getPassengerBalance());
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /** 通用原子转账（取消费等）：from 余额守卫扣款 + to 入账 + 双流水 + 幂等，同事务。 */
    public PayResultDTO transfer(TransferRequest request) {
        if (request.getFromUserId() == null || request.getToUserId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "转出/转入用户 ID 必填");
        }
        if (request.getBizType() == null || request.getBizType().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "业务类型必填");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0 : request.getAmount());
        String requestId = requireKey(request.getRequestId(), "requestId");
        checkLimit(amount, properties.getRisk().getTransferMaxPerTxn(), "划转");
        String bizType = request.getBizType().trim().toUpperCase();

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(requestId);
        if (existing != null && existing.getResultJson() != null) {
            log.info("转账幂等命中 requestId={}", requestId);
            PayResultDTO repeated = JsonUtil.fromJson(existing.getResultJson(), PayResultDTO.class);
            repeated.setRepeated(true);
            return repeated;
        }
        long now = System.currentTimeMillis();
        try {
            PayResultDTO result = transactionTemplate.execute(s -> {
                walletMapper.ensure(request.getFromUserId(), now);
                if (walletMapper.debit(request.getFromUserId(), amount, now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "转出方余额不足");
                }
                recordLedger(request.getFromUserId(), request.getOrderId(), bizType + "_DEBIT", -amount, requestId, now);
                walletMapper.ensure(request.getToUserId(), now);
                walletMapper.credit(request.getToUserId(), amount, now);
                recordLedger(request.getToUserId(), request.getOrderId(), bizType + "_CREDIT", amount, requestId, now);

                PayResultDTO dto = new PayResultDTO();
                dto.setOrderId(request.getOrderId());
                dto.setAmount(amount);
                dto.setFee(0L);
                dto.setDriverIncome(amount);
                dto.setPassengerBalance(walletMapper.selectByUserId(request.getFromUserId()).getBalance());
                dto.setRepeated(false);
                idempotentMapper.insert(new IdempotentRecordDO(requestId, "TRANSFER", JsonUtil.toJson(dto), now));
                mirror("TRANSFER", requestId, request.getFromUserId(), request.getOrderId(), now);
                return dto;
            });
            log.info("资金划转 bizType={} orderId={} from={} to={} amount={}",
                    bizType, request.getOrderId(), request.getFromUserId(), request.getToUserId(), amount);
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /**
     * 退款（支持部分退款）：事务内先校验原单支付流水与累计可退余额（杜绝退未付单/超退），
     * 抽成按退款额占原支付额比例折算（可显式覆盖）；司机余额不足时按可用余额部分追回，
     * 缺口由平台兜底，保证乘客本次退款全额到账；事件与划转同事务落 refund_outbox。
     */
    public PayResultDTO refund(RefundRequest request) {
        if (request.getOrderId() == null || request.getPassengerId() == null || request.getDriverId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单/乘客/司机 ID 必填");
        }
        long amount = MoneyUtil.requireValid(request.getAmount() == null ? 0 : request.getAmount());
        String requestId = requireKey(request.getRequestId(), "requestId");
        checkLimit(amount, properties.getRisk().getRefundMaxPerTxn(), "退款");

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(requestId);
        if (existing != null && existing.getResultJson() != null) {
            log.info("退款幂等命中 requestId={}", requestId);
            PayResultDTO repeated = JsonUtil.fromJson(existing.getResultJson(), PayResultDTO.class);
            repeated.setRepeated(true);
            return repeated;
        }
        String platform = properties.getPlatformUserId();
        long now = System.currentTimeMillis();
        try {
            PayResultDTO result = transactionTemplate.execute(s -> {
                LedgerEntryDO debit = ledgerMapper.selectByOrderAndBizType(request.getOrderId(), "PAY_DEBIT");
                if (debit == null) {
                    throw new BizException(ResultCode.CONFLICT, "订单无支付流水，不可退款: " + request.getOrderId());
                }
                long paidAmount = -debit.getAmount();
                long refunded = ledgerMapper.sumRefundCredit(request.getOrderId());
                if (amount > paidAmount - refunded) {
                    throw new BizException(ResultCode.CONFLICT,
                            "退款金额超过可退余额: 本次=" + amount + " 可退=" + (paidAmount - refunded));
                }
                LedgerEntryDO feeEntry = ledgerMapper.selectByOrderAndBizType(request.getOrderId(), "PLATFORM_FEE");
                long originalFee = feeEntry == null ? 0 : feeEntry.getAmount();
                long fee = request.getFee() != null ? request.getFee()
                        : Math.round(originalFee * (double) amount / paidAmount);
                if (fee < 0 || fee > amount) {
                    throw new BizException(ResultCode.PARAM_ERROR, "退款抽成非法: " + fee);
                }
                long driverIncome = amount - fee;

                walletMapper.ensure(request.getDriverId(), now);
                walletMapper.ensure(platform, now);
                walletMapper.ensure(request.getPassengerId(), now);

                WalletDO driverWallet = walletMapper.selectByUserId(request.getDriverId());
                long driverAvailable = driverWallet.getBalance() - driverWallet.getFrozenAmount();
                long driverClawback = Math.min(driverIncome, Math.max(0, driverAvailable));
                long platformPart = amount - driverClawback;
                if (driverClawback > 0) {
                    if (walletMapper.debit(request.getDriverId(), driverClawback, now) == 0) {
                        throw new BizException(ResultCode.CONFLICT, "司机退款扣回失败");
                    }
                    recordLedger(request.getDriverId(), request.getOrderId(), "REFUND_DEBIT", -driverClawback, requestId, now);
                }
                if (platformPart > 0) {
                    if (walletMapper.debit(platform, platformPart, now) == 0) {
                        throw new BizException(ResultCode.CONFLICT, "平台余额不足，无法兜底退款");
                    }
                    recordLedger(platform, request.getOrderId(), "REFUND_FEE_DEBIT", -platformPart, requestId, now);
                }
                walletMapper.credit(request.getPassengerId(), amount, now);
                recordLedger(request.getPassengerId(), request.getOrderId(), "REFUND_CREDIT", amount, requestId, now);

                PayResultDTO dto = new PayResultDTO();
                dto.setOrderId(request.getOrderId());
                dto.setAmount(amount);
                dto.setFee(platformPart);
                dto.setDriverIncome(driverClawback);
                dto.setPassengerBalance(walletMapper.selectByUserId(request.getPassengerId()).getBalance());
                dto.setRepeated(false);
                idempotentMapper.insert(new IdempotentRecordDO(requestId, "REFUND", JsonUtil.toJson(dto), now));
                refundOutboxMapper.insert(new RefundOutboxDO(requestId, request.getOrderId(), "NEW", 0, 0L, now));
                mirror("REFUND", requestId, request.getPassengerId(), request.getOrderId(), now);
                return dto;
            });
            log.info("订单退款 orderId={} requestId={} amount={} driverClawback={} platformPart={} passengerBalance={}",
                    request.getOrderId(), requestId, amount, result.getDriverIncome(), result.getFee(), result.getPassengerBalance());
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /** PAY_DEBIT 流水与扣款同事务提交，流水存在即该订单的钱已划转。 */
    public boolean isPaid(String orderId) {
        return ledgerMapper.countPayDebit(orderId) > 0;
    }

    /** REFUND_CREDIT 流水与退款划转同事务提交，流水在即退款已到账。 */
    public boolean isRefunded(String orderId) {
        return ledgerMapper.countRefundCredit(orderId) > 0;
    }

    /** 支付结果快照：读 PAY-{orderId} 幂等记录（订单域推送司机实收用）。 */
    public PayResultDTO payResult(String orderId) {
        IdempotentRecordDO rec = idempotentMapper.selectByRequestId("PAY-" + orderId);
        return rec == null || rec.getResultJson() == null ? null : JsonUtil.fromJson(rec.getResultJson(), PayResultDTO.class);
    }

    /** 累计退款摘要（流水聚合，权威值），供订单域对账同步部分退款状态。 */
    public RefundSummaryDTO refundSummary(String orderId) {
        RefundSummaryDTO dto = new RefundSummaryDTO();
        dto.setOrderId(orderId);
        dto.setTotalAmount(ledgerMapper.sumRefundCredit(orderId));
        dto.setCount(ledgerMapper.countRefundCredit(orderId));
        return dto;
    }

    public PageDTO<LedgerEntryDTO> ledger(LedgerQuery query) {
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? 20 : Math.min(query.getSize(), 100);
        Map<String, Object> params = new HashMap<>();
        if (query.getUserId() != null && !query.getUserId().isBlank()) {
            params.put("userId", query.getUserId());
        }
        if (query.getOrderId() != null && !query.getOrderId().isBlank()) {
            params.put("orderId", query.getOrderId());
        }
        if (query.getBizType() != null && !query.getBizType().isBlank()) {
            params.put("bizType", query.getBizType());
        }
        params.put("from", query.getFrom());
        params.put("to", query.getTo());
        params.put("size", size);
        params.put("offset", (page - 1) * size);
        long total = ledgerMapper.count(params);
        List<LedgerEntryDTO> items = ledgerMapper.query(params).stream().map(this::toLedgerDTO).toList();
        return PageDTO.of(items, total, page, size);
    }

    /** 账面核对：wallet 余额应等于该户全部流水之和（每笔动账必落流水）。 */
    public List<WalletAuditDTO> audit() {
        Map<String, long[]> sums = new HashMap<>();
        for (Map<String, Object> row : ledgerMapper.sumByUser()) {
            sums.put(String.valueOf(row.get("user_id")), new long[]{
                    ((Number) row.get("ledger_sum")).longValue(),
                    ((Number) row.get("entry_count")).longValue()});
        }
        List<WalletAuditDTO> result = new ArrayList<>();
        for (WalletDO wallet : walletMapper.selectAll()) {
            long[] sum = sums.getOrDefault(wallet.getUserId(), new long[]{0, 0});
            WalletAuditDTO dto = new WalletAuditDTO();
            dto.setUserId(wallet.getUserId());
            dto.setWalletBalance(wallet.getBalance());
            dto.setLedgerSum(sum[0]);
            dto.setDiff(wallet.getBalance() - sum[0]);
            dto.setOk(wallet.getBalance() == sum[0]);
            dto.setEntryCount(sum[1]);
            result.add(dto);
        }
        return result;
    }

    public List<PaymentStatsDTO> stats() {
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<PaymentStatsDTO> result = new ArrayList<>();
        for (Map<String, Object> row : ledgerMapper.stats(todayStart)) {
            PaymentStatsDTO dto = new PaymentStatsDTO();
            dto.setBizType(String.valueOf(row.get("biz_type")));
            dto.setTotalAmount(((Number) row.get("total_amount")).longValue());
            dto.setEntryCount(((Number) row.get("entry_count")).longValue());
            dto.setTodayAmount(((Number) row.get("today_amount")).longValue());
            result.add(dto);
        }
        return result;
    }

    public OutboxStatsDTO outboxStats() {
        OutboxStatsDTO dto = new OutboxStatsDTO();
        dto.setPendingCount(outboxMapper.countByStatus("NEW") + refundOutboxMapper.countByStatus("NEW"));
        dto.setSentCount(outboxMapper.countByStatus("SENT") + refundOutboxMapper.countByStatus("SENT"));
        dto.setDeadCount(outboxMapper.countByStatus("DEAD") + refundOutboxMapper.countByStatus("DEAD"));
        dto.setMaxRetries(Math.max(outboxMapper.maxRetries(), refundOutboxMapper.maxRetries()));
        long now = System.currentTimeMillis();
        Long oldest = null;
        for (Long candidate : new Long[]{outboxMapper.oldestPendingCreatedAt(), refundOutboxMapper.oldestPendingCreatedAt()}) {
            if (candidate != null && (oldest == null || candidate < oldest)) {
                oldest = candidate;
            }
        }
        dto.setOldestPendingAgeMs(oldest == null ? 0L : now - oldest);
        return dto;
    }

    public List<OutboxEntryDTO> deadOutbox() {
        List<OutboxEntryDTO> result = new ArrayList<>();
        for (PayOutboxDO row : outboxMapper.selectDead()) {
            result.add(toOutboxEntry("PAY", row.getOrderId(), row.getRequestId(), row.getStatus(),
                    row.getRetries(), row.getNextRetryAt(), row.getCreatedAt()));
        }
        for (RefundOutboxDO row : refundOutboxMapper.selectDead()) {
            result.add(toOutboxEntry("REFUND", row.getOrderId(), row.getRequestId(), row.getStatus(),
                    row.getRetries(), row.getNextRetryAt(), row.getCreatedAt()));
        }
        result.sort(Comparator.comparing(OutboxEntryDTO::getCreatedAt));
        return result;
    }

    /** 人工重放 DEAD 事件：重置 NEW 由投递器重新驱动（订单域入口幂等，重放安全）。 */
    public int replayOutbox(String key) {
        if (key == null || key.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "重放键必填（orderId 或 requestId）");
        }
        int count = outboxMapper.replay(key) + refundOutboxMapper.replay(key);
        log.info("outbox 人工重放 key={} reset={}", key, count);
        return count;
    }

    private void recordLedger(String userId, String orderId, String bizType, long amount, String requestId, long now) {
        WalletDO wallet = walletMapper.selectByUserId(userId);
        LedgerEntryDO entry = new LedgerEntryDO();
        entry.setId("L" + ledgerMapper.nextId());
        entry.setUserId(userId);
        entry.setOrderId(orderId);
        entry.setBizType(bizType);
        entry.setAmount(amount);
        entry.setBalanceAfter(wallet.getBalance());
        entry.setRequestId(requestId);
        entry.setCreatedAt(now);
        ledgerMapper.insert(entry);
    }

    private WalletDTO repeatedWallet(String bizNo) {
        IdempotentRecordDO rec = idempotentMapper.selectByRequestId(bizNo);
        if (rec != null && rec.getResultJson() != null) {
            return JsonUtil.fromJson(rec.getResultJson(), WalletDTO.class);
        }
        throw new BizException(ResultCode.CONFLICT, "重复请求处理中: " + bizNo);
    }

    private PayResultDTO repeatedPay(String requestId) {
        IdempotentRecordDO rec = idempotentMapper.selectByRequestId(requestId);
        if (rec != null && rec.getResultJson() != null) {
            PayResultDTO repeated = JsonUtil.fromJson(rec.getResultJson(), PayResultDTO.class);
            repeated.setRepeated(true);
            return repeated;
        }
        throw new BizException(ResultCode.CONFLICT, "重复资金请求处理中: " + requestId);
    }

    private WalletDTO toWallet(WalletDO wallet) {
        WalletDTO dto = new WalletDTO();
        dto.setUserId(wallet.getUserId());
        dto.setBalance(wallet.getBalance());
        dto.setFrozenAmount(wallet.getFrozenAmount() == null ? 0L : wallet.getFrozenAmount());
        dto.setAvailableBalance(dto.getBalance() - dto.getFrozenAmount());
        dto.setUpdatedAt(wallet.getUpdatedAt());
        return dto;
    }

    private WithdrawOrderDTO toWithdrawDTO(WithdrawOrderDO row) {
        WithdrawOrderDTO dto = new WithdrawOrderDTO();
        dto.setId(row.getId());
        dto.setUserId(row.getUserId());
        dto.setAmount(row.getAmount());
        dto.setStatus(row.getStatus());
        dto.setBizNo(row.getBizNo());
        dto.setFailReason(row.getFailReason());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setUpdatedAt(row.getUpdatedAt());
        return dto;
    }

    private OutboxEntryDTO toOutboxEntry(String type, String orderId, String requestId, String status,
                                         Integer retries, Long nextRetryAt, Long createdAt) {
        OutboxEntryDTO dto = new OutboxEntryDTO();
        dto.setType(type);
        dto.setOrderId(orderId);
        dto.setRequestId(requestId);
        dto.setStatus(status);
        dto.setRetries(retries);
        dto.setNextRetryAt(nextRetryAt);
        dto.setCreatedAt(createdAt);
        return dto;
    }

    private LedgerEntryDTO toLedgerDTO(LedgerEntryDO entry) {
        LedgerEntryDTO dto = new LedgerEntryDTO();
        dto.setId(entry.getId());
        dto.setUserId(entry.getUserId());
        dto.setOrderId(entry.getOrderId());
        dto.setBizType(entry.getBizType());
        dto.setAmount(entry.getAmount());
        dto.setBalanceAfter(entry.getBalanceAfter());
        dto.setRequestId(entry.getRequestId());
        dto.setCreatedAt(entry.getCreatedAt());
        return dto;
    }

    /** 幂等键必填：随机兜底会让重试变成新请求（重复充值/提现），必须由调用方提供确定性业务号。 */
    private String requireKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, name + " 必填（幂等键，重试须复用同一值）");
        }
        return value;
    }

    private void checkLimit(long amount, long limit, String biz) {
        if (amount > limit) {
            throw new BizException(ResultCode.CONFLICT, biz + "超出单笔限额: " + amount + " > " + limit);
        }
    }
}