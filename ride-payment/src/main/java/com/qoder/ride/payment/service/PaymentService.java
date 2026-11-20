package com.qoder.ride.payment.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.common.util.MoneyUtil;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.payment.LedgerCorrectionRequest;
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
import com.qoder.ride.payment.client.PaymentProRejectedException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包与资金划转（v1.6.0 同步回执流程）。
 *
 * 集成 payment-pro 时：先落 PENDING 流水并冻结出资方资金（同事务）→ 同步调用 payment-pro OpenAPI
 * → 拿到回执后流水转 CONFIRMED、钱包才真正动账（同事务）；支付系统明确拒绝则流水 FAILED、解冻退回；
 * 通信异常保留 PENDING，由 MirrorDispatcher 恢复任务凭幂等键续传补确认。崩溃窗口两侧均有留痕可对账。
 *
 * 未配置集成（standalone）：保持单本地事务原子动账，流水直接 CONFIRMED。
 * 幂等键必填防重复入账；退款校验原单支付流水与累计可退余额；提现走冻结→模拟渠道→同步出金回执。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** 同步请求宽限期：期内由请求线程自行完成回执确认，超期未决的 PENDING 由恢复任务接管。 */
    private static final long SYNC_GRACE_MS = 15_000L;
    /** 外部银行卡清算户（对标 payment-pro 的 SYS_CASH）：CARD 支付的出资方，非用户钱包，允许透支。 */
    static final String CARD_CLEARING_USER = "SYS_CARD";

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
    private final MirrorExecutor mirrorExecutor;

    /** 一笔资金操作的借贷腿计划（amount 带符号）。 */
    private record Leg(String userId, String orderId, String bizType, long amount) {
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
        List<Leg> legs = List.of(new Leg(request.getUserId(), null, "TOPUP", amount));
        try {
            String json = run("TOPUP", bizNo, request.getUserId(), null, legs);
            WalletDTO dto = JsonUtil.fromJson(json, WalletDTO.class);
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

    /** 提现受理：风控校验→冻结可用余额→落提现单（ACCEPTED），模拟渠道异步驱动终态（终态前同步调用支付系统出金）。 */
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
     * 提现终态（模拟渠道回调，WithdrawProcessor 调度）。
     * 成功：先落 PENDING WITHDRAW 流水 → 同步调用 payment-pro 出金 → 回执后扣款+解冻+流水 CONFIRMED；
     * 支付系统拒绝（如余额不足）→ 提现单 FAILED、解冻退回，不再产生"本地成功、对端无记录"的 DEAD 镜像。
     * 失败率按 bizNo 哈希确定性判定，重试不翻转；崩溃后 PENDING 由恢复任务续传。
     */
    public void settleWithdraw(WithdrawOrderDO row) {
        long now = System.currentTimeMillis();
        boolean channelFail = Math.floorMod(row.getBizNo().hashCode(), 10000) < properties.getWithdrawFailBps();
        if (channelFail) {
            transactionTemplate.executeWithoutResult(s -> {
                if (withdrawOrderMapper.fail(row.getId(), "模拟渠道拒绝", now) == 0) {
                    return;
                }
                walletMapper.unfreeze(row.getUserId(), row.getAmount(), now);
                idempotentMapper.merge(new IdempotentRecordDO(row.getBizNo(), "WITHDRAW",
                        JsonUtil.toJson(toWallet(walletMapper.selectByUserId(row.getUserId()))), now));
            });
            log.warn("提现失败已解冻 withdrawId={} userId={} amount={}", row.getId(), row.getUserId(), row.getAmount());
            return;
        }
        if (!integration.isEnabled()) {
            transactionTemplate.executeWithoutResult(s -> {
                if (withdrawOrderMapper.casStatus(row.getId(), "PROCESSING", "SUCCESS", now) == 0) {
                    return;
                }
                if (walletMapper.debitAndUnfreeze(row.getUserId(), row.getAmount(), now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "提现扣款失败（冻结不足）: " + row.getId());
                }
                recordLedger(row.getUserId(), null, "WITHDRAW", -row.getAmount(), row.getBizNo(), now, "CONFIRMED", null);
                idempotentMapper.merge(new IdempotentRecordDO(row.getBizNo(), "WITHDRAW",
                        JsonUtil.toJson(toWallet(walletMapper.selectByUserId(row.getUserId()))), now));
            });
            log.info("提现成功 withdrawId={} userId={} amount={}", row.getId(), row.getUserId(), row.getAmount());
            return;
        }

        String mirrorKey = "M-WITHDRAW-" + row.getBizNo();
        MirrorOutboxDO mirror = mirrorOutboxMapper.selectByBizNo(mirrorKey);
        if (mirror == null) {
            try {
                mirror = transactionTemplate.execute(s -> {
                    recordLedger(row.getUserId(), null, "WITHDRAW", -row.getAmount(), row.getBizNo(), now, "PENDING", null);
                    MirrorOutboxDO created = new MirrorOutboxDO(mirrorKey, "WITHDRAW", row.getBizNo(),
                            row.getUserId(), null, "NEW", 0, now + SYNC_GRACE_MS, null, null, null, now);
                    mirrorOutboxMapper.insert(created);
                    return created;
                });
            } catch (DuplicateKeyException e) {
                mirror = mirrorOutboxMapper.selectByBizNo(mirrorKey);
            }
        }
        if (mirror == null || !"NEW".equals(mirror.getStatus())) {
            return;
        }
        try {
            String receipt = mirrorExecutor.execute(mirror);
            confirmMirror(mirror, receipt);
            log.info("提现成功（支付系统回执） withdrawId={} userId={} amount={} receipt={}",
                    row.getId(), row.getUserId(), row.getAmount(), receipt);
        } catch (PaymentProRejectedException e) {
            failMirror(mirror, e.getMessage());
            log.warn("提现失败（支付系统拒绝） withdrawId={} userId={}: {}", row.getId(), row.getUserId(), e.getMessage());
        } catch (Exception e) {
            log.warn("提现支付系统通信异常，PENDING 待恢复续传 withdrawId={}: {}", row.getId(), e.getMessage());
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
        // 银行卡出资走外部清算户：payment-pro 的 CARD 单不扣 BALANCE 户（钱来自 SYS_CASH），
        // 本地若仍扣乘客钱包就会与对端永久不平。清算户不是用户钱包，允许透支。
        boolean card = "CARD".equalsIgnoreCase(request.getMethod());
        String fundingUser = card ? CARD_CLEARING_USER : request.getPassengerId();
        List<Leg> legs = List.of(
                new Leg(fundingUser, request.getOrderId(), "PAY_DEBIT", -amount),
                new Leg(request.getDriverId(), request.getOrderId(), "PAY_CREDIT", driverIncome),
                new Leg(platform, request.getOrderId(), "PLATFORM_FEE", fee));
        try {
            PayResultDTO result = JsonUtil.fromJson(run("PAY", requestId, request.getPassengerId(), request.getOrderId(), legs),
                    PayResultDTO.class);
            log.info("行程支付 orderId={} amount={} fee={} driverIncome={} passengerBalance={}",
                    request.getOrderId(), amount, fee, driverIncome, result.getPassengerBalance());
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /** 通用原子转账（取消费等）：from 扣款 + to 入账 + 双流水 + 幂等。 */
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
        List<Leg> legs = List.of(
                new Leg(request.getFromUserId(), request.getOrderId(), bizType + "_DEBIT", -amount),
                new Leg(request.getToUserId(), request.getOrderId(), bizType + "_CREDIT", amount));
        try {
            PayResultDTO result = JsonUtil.fromJson(run("TRANSFER", requestId, request.getFromUserId(), request.getOrderId(), legs),
                    PayResultDTO.class);
            log.info("资金划转 bizType={} orderId={} from={} to={} amount={}",
                    bizType, request.getOrderId(), request.getFromUserId(), request.getToUserId(), amount);
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /**
     * 双账本历史冲正：本地多腿更正，借贷必相等，只增冲正腿不改原行，不镜像 payment-pro
     * （对端账务本就正确，或已单独冲正）。用于修复「CARD 误扣乘客钱包」与
     * 「pp 终态 FAILED 但本地已确认」两类历史缺口。requestId 幂等，重复提交不重复动账。
     */
    public Map<String, Object> correctLedger(LedgerCorrectionRequest request) {
        String requestId = requireKey(request.getRequestId(), "requestId");
        String reason = request.getReason();
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "冲正事由必填");
        }
        List<LedgerCorrectionRequest.Leg> reqLegs = request.getLegs();
        if (reqLegs == null || reqLegs.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "冲正腿不能为空");
        }
        long sum = 0;
        for (LedgerCorrectionRequest.Leg leg : reqLegs) {
            if (leg.getUserId() == null || leg.getUserId().isBlank()
                    || leg.getBizType() == null || leg.getBizType().isBlank()
                    || leg.getAmount() == null || leg.getAmount() == 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "冲正腿 userId/bizType/amount 必填且金额非零");
            }
            sum += leg.getAmount();
        }
        if (sum != 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "冲正借贷必须相等，当前净额=" + sum);
        }
        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(requestId);
        if (existing != null && existing.getResultJson() != null) {
            log.info("冲正幂等命中 requestId={}", requestId);
            @SuppressWarnings("unchecked")
            Map<String, Object> repeated = JsonUtil.fromJson(existing.getResultJson(), Map.class);
            repeated.put("repeated", true);
            return repeated;
        }
        long now = System.currentTimeMillis();
        List<Leg> legs = reqLegs.stream()
                .map(l -> new Leg(l.getUserId(), request.getOrderId(), l.getBizType(), l.getAmount()))
                .toList();
        Map<String, Object> out = transactionTemplate.execute(s -> {
            applyLegs(legs, requestId, now);
            Map<String, Object> balances = new LinkedHashMap<>();
            for (Leg leg : legs) {
                balances.put(leg.userId(), walletMapper.selectByUserId(leg.userId()).getBalance());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestId", requestId);
            result.put("orderId", request.getOrderId());
            result.put("reason", reason);
            result.put("legs", reqLegs);
            result.put("balances", balances);
            result.put("repeated", false);
            idempotentMapper.insert(new IdempotentRecordDO(requestId, "CORRECTION", JsonUtil.toJson(result), now));
            return result;
        });
        log.warn("双账本历史冲正 requestId={} orderId={} reason={} legs={} balances={}",
                requestId, request.getOrderId(), reason, JsonUtil.toJson(reqLegs), out.get("balances"));
        return out;
    }

    /**
     * 退款（支持部分退款）：先校验原单支付流水与累计可退余额（杜绝退未付单/超退），
     * 抽成按退款额占原支付额比例折算（可显式覆盖）；司机余额不足时按可用余额部分追回，
     * 缺口由平台兜底，保证乘客本次退款全额到账。集成模式下出资腿先冻结，回执确认后动账。
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

        walletMapper.ensure(request.getDriverId(), System.currentTimeMillis());
        WalletDO driverWallet = walletMapper.selectByUserId(request.getDriverId());
        long driverAvailable = driverWallet.getBalance() - driverWallet.getFrozenAmount();
        long driverClawback = Math.min(driverIncome, Math.max(0, driverAvailable));
        long platformPart = amount - driverClawback;

        List<Leg> legs = new ArrayList<>();
        if (driverClawback > 0) {
            legs.add(new Leg(request.getDriverId(), request.getOrderId(), "REFUND_DEBIT", -driverClawback));
        }
        if (platformPart > 0) {
            legs.add(new Leg(platform, request.getOrderId(), "REFUND_FEE_DEBIT", -platformPart));
        }
        legs.add(new Leg(request.getPassengerId(), request.getOrderId(), "REFUND_CREDIT", amount));
        try {
            PayResultDTO result = JsonUtil.fromJson(run("REFUND", requestId, request.getPassengerId(), request.getOrderId(), legs),
                    PayResultDTO.class);
            log.info("订单退款 orderId={} requestId={} amount={} driverClawback={} platformPart={} passengerBalance={}",
                    request.getOrderId(), requestId, amount, result.getDriverIncome(), result.getFee(), result.getPassengerBalance());
            return result;
        } catch (DuplicateKeyException e) {
            return repeatedPay(requestId);
        }
    }

    /**
     * 资金操作统一入口：standalone 单事务原子动账；集成模式走 PENDING→同步调用→回执确认。
     * 返回幂等记录中的结果 JSON。DuplicateKeyException 透传给调用方做幂等回读。
     */
    private String run(String bizType, String refKey, String userId, String orderId, List<Leg> legs) {
        long now = System.currentTimeMillis();
        if (!integration.isEnabled()) {
            return transactionTemplate.execute(s -> {
                applyLegs(legs, refKey, now);
                String json = buildResultJson(bizType, userId, orderId, legs);
                idempotentMapper.insert(new IdempotentRecordDO(refKey, bizType, json, now));
                insertDomainOutbox(bizType, refKey, orderId, now);
                return json;
            });
        }
        MirrorOutboxDO mirror = transactionTemplate.execute(s -> {
            idempotentMapper.insert(new IdempotentRecordDO(refKey, bizType, null, now));
            for (Leg leg : legs) {
                walletMapper.ensure(leg.userId(), now);
                if (leg.amount() < 0 && !CARD_CLEARING_USER.equals(leg.userId())
                        && walletMapper.freeze(leg.userId(), -leg.amount(), now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "可用余额不足: " + leg.userId());
                }
                recordLedger(leg.userId(), leg.orderId(), leg.bizType(), leg.amount(), refKey, now, "PENDING", null);
            }
            MirrorOutboxDO created = new MirrorOutboxDO("M-" + bizType + "-" + refKey, bizType, refKey, userId, orderId,
                    "NEW", 0, now + SYNC_GRACE_MS, null, null, null, now);
            mirrorOutboxMapper.upsert(created);
            return created;
        });
        try {
            String receipt = mirrorExecutor.execute(mirror);
            confirmMirror(mirror, receipt);
            return idempotentMapper.selectByRequestId(refKey).getResultJson();
        } catch (PaymentProRejectedException e) {
            failMirror(mirror, e.getMessage());
            log.warn("资金操作被支付系统拒绝 bizType={} refKey={}: {}", bizType, refKey, e.getMessage());
            throw new BizException(ResultCode.CONFLICT, "支付系统拒绝: " + e.getMessage());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("支付系统通信异常，PENDING 待恢复续传 bizType={} refKey={}: {}", bizType, refKey, e.getMessage());
            throw new BizException(ResultCode.CONFLICT,
                    "支付系统通信异常，流水已受理（PENDING），后台将自动续传确认: " + e.getMessage());
        }
    }

    /**
     * 回执确认（同步路径与恢复路径共用）：PENDING 流水逐腿动账转 CONFIRMED、回填余额与回执号，
     * 镜像置 SENT，支付/退款落域 outbox 通知订单域，提现单转 SUCCESS，幂等结果回填。
     * 以镜像状态 NEW 为 CAS 守卫，保证只确认一次。
     */
    public void confirmMirror(MirrorOutboxDO row, String receipt) {
        long now = System.currentTimeMillis();
        transactionTemplate.executeWithoutResult(s -> {
            MirrorOutboxDO current = mirrorOutboxMapper.selectByBizNo(row.getBizNo());
            if (current == null || !"NEW".equals(current.getStatus())) {
                return;
            }
            List<LedgerEntryDO> legs = mirrorExecutor.pendingLegs(row.getRefKey());
            List<Leg> plan = new ArrayList<>();
            for (LedgerEntryDO leg : legs) {
                if (leg.getAmount() < 0) {
                    if (CARD_CLEARING_USER.equals(leg.getUserId())) {
                        walletMapper.debitClearing(leg.getUserId(), -leg.getAmount(), now);
                    } else if (walletMapper.debitAndUnfreeze(leg.getUserId(), -leg.getAmount(), now) == 0) {
                        throw new BizException(ResultCode.CONFLICT, "回执确认扣款失败（冻结不足）: " + leg.getUserId());
                    }
                } else if (leg.getAmount() > 0) {
                    walletMapper.ensure(leg.getUserId(), now);
                    walletMapper.credit(leg.getUserId(), leg.getAmount(), now);
                }
                long balance = walletMapper.selectByUserId(leg.getUserId()).getBalance();
                ledgerMapper.confirm(leg.getId(), balance, receipt);
                plan.add(new Leg(leg.getUserId(), leg.getOrderId(), leg.getBizType(), leg.getAmount()));
            }
            mirrorOutboxMapper.markSent(row.getBizNo());
            insertDomainOutbox(row.getBizType(), row.getRefKey(), row.getOrderId(), now);
            if ("WITHDRAW".equals(row.getBizType())) {
                WithdrawOrderDO withdraw = withdrawOrderMapper.selectByBizNo(row.getRefKey());
                if (withdraw != null) {
                    withdrawOrderMapper.casStatus(withdraw.getId(), "PROCESSING", "SUCCESS", now);
                }
            }
            idempotentMapper.merge(new IdempotentRecordDO(row.getRefKey(), row.getBizType(),
                    buildResultJson(row.getBizType(), row.getUserId(), row.getOrderId(), plan), now));
        });
        log.info("支付系统回执确认 bizNo={} type={} receipt={}", row.getBizNo(), row.getBizType(), receipt);
    }

    /** 支付系统明确拒绝：解冻出资腿、流水 FAILED、镜像 FAILED、清除幂等占位允许重试；提现单转 FAILED。 */
    public void failMirror(MirrorOutboxDO row, String error) {
        long now = System.currentTimeMillis();
        transactionTemplate.executeWithoutResult(s -> {
            MirrorOutboxDO current = mirrorOutboxMapper.selectByBizNo(row.getBizNo());
            if (current == null || !"NEW".equals(current.getStatus())) {
                return;
            }
            rollbackLocal(row, now);
            mirrorOutboxMapper.markFailed(row.getBizNo(), truncate(error));
        });
        log.warn("资金操作终态失败 bizNo={} type={}: {}", row.getBizNo(), row.getBizType(), truncate(error));
    }

    /** 管理端人工关闭 DEAD 事件：本地 PENDING 同样退回（解冻+流水 FAILED），镜像置 CLOSED 留痕原因。 */
    public int abandonMirror(String bizNo, String reason) {
        MirrorOutboxDO row = mirrorOutboxMapper.selectByBizNo(bizNo);
        if (row == null || !"DEAD".equals(row.getStatus())) {
            return 0;
        }
        long now = System.currentTimeMillis();
        transactionTemplate.executeWithoutResult(s -> {
            rollbackLocal(row, now);
            mirrorOutboxMapper.close(bizNo, reason);
        });
        log.warn("镜像 DEAD 人工关闭并退回本地 bizNo={} reason={}", bizNo, reason);
        return 1;
    }

    private void rollbackLocal(MirrorOutboxDO row, long now) {
        for (LedgerEntryDO leg : ledgerMapper.selectByRequestId(row.getRefKey())) {
            if (!"PENDING".equals(leg.getStatus())) {
                continue;
            }
            if (leg.getAmount() < 0 && !CARD_CLEARING_USER.equals(leg.getUserId())) {
                walletMapper.unfreeze(leg.getUserId(), -leg.getAmount(), now);
            }
            ledgerMapper.fail(leg.getId());
        }
        if ("WITHDRAW".equals(row.getBizType())) {
            WithdrawOrderDO withdraw = withdrawOrderMapper.selectByBizNo(row.getRefKey());
            if (withdraw != null) {
                withdrawOrderMapper.fail(withdraw.getId(), "支付系统拒绝出金", now);
            }
            idempotentMapper.merge(new IdempotentRecordDO(row.getRefKey(), "WITHDRAW",
                    JsonUtil.toJson(toWallet(walletMapper.selectByUserId(row.getUserId()))), now));
        } else {
            idempotentMapper.deletePending(row.getRefKey());
        }
    }

    /** standalone 动账：出资腿可用余额守卫扣款，入账腿贷记，流水直接 CONFIRMED。 */
    private void applyLegs(List<Leg> legs, String refKey, long now) {
        for (Leg leg : legs) {
            walletMapper.ensure(leg.userId(), now);
            if (leg.amount() < 0) {
                if (CARD_CLEARING_USER.equals(leg.userId())) {
                    walletMapper.debitClearing(leg.userId(), -leg.amount(), now);
                } else if (walletMapper.debit(leg.userId(), -leg.amount(), now) == 0) {
                    throw new BizException(ResultCode.CONFLICT, "可用余额不足: " + leg.userId());
                }
            } else if (leg.amount() > 0) {
                walletMapper.credit(leg.userId(), leg.amount(), now);
            }
            recordLedger(leg.userId(), leg.orderId(), leg.bizType(), leg.amount(), refKey, now, "CONFIRMED", null);
        }
    }

    private void insertDomainOutbox(String bizType, String refKey, String orderId, long now) {
        if ("PAY".equals(bizType)) {
            outboxMapper.insert(new PayOutboxDO(orderId, refKey, "NEW", 0, 0L, now));
        } else if ("REFUND".equals(bizType)) {
            refundOutboxMapper.insert(new RefundOutboxDO(refKey, orderId, "NEW", 0, 0L, now));
        }
    }

    /** PAY_DEBIT 流水与扣款同事务提交（仅 CONFIRMED），流水存在即该订单的钱已划转。 */
    public boolean isPaid(String orderId) {
        return ledgerMapper.countPayDebit(orderId) > 0;
    }

    /** REFUND_CREDIT 流水与退款划转同事务提交（仅 CONFIRMED），流水在即退款已到账。 */
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

    /** 账面核对：wallet 余额应等于该户全部 CONFIRMED 流水之和（每笔动账必落流水）。 */
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

    private void recordLedger(String userId, String orderId, String bizType, long amount, String requestId,
                              long now, String status, String ppReceipt) {
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
        entry.setStatus(status);
        entry.setPpReceipt(ppReceipt);
        ledgerMapper.insert(entry);
    }

    /** 由借贷腿计划与当前钱包构建操作结果 JSON（充值/提现=钱包视图，其余=支付结果视图）。 */
    private String buildResultJson(String bizType, String userId, String orderId, List<Leg> legs) {
        if ("TOPUP".equals(bizType) || "WITHDRAW".equals(bizType)) {
            return JsonUtil.toJson(toWallet(walletMapper.selectByUserId(userId)));
        }
        PayResultDTO dto = new PayResultDTO();
        dto.setOrderId(orderId);
        dto.setRepeated(false);
        if ("PAY".equals(bizType)) {
            dto.setAmount(-sum(legs, "PAY_DEBIT"));
            dto.setFee(sum(legs, "PLATFORM_FEE"));
            dto.setDriverIncome(sum(legs, "PAY_CREDIT"));
        } else if ("REFUND".equals(bizType)) {
            dto.setAmount(sum(legs, "REFUND_CREDIT"));
            dto.setFee(-sum(legs, "REFUND_FEE_DEBIT"));
            dto.setDriverIncome(-sum(legs, "REFUND_DEBIT"));
        } else {
            long amount = legs.stream().filter(l -> l.amount() < 0).mapToLong(l -> -l.amount()).sum();
            dto.setAmount(amount);
            dto.setFee(0L);
            dto.setDriverIncome(amount);
        }
        dto.setPassengerBalance(walletMapper.selectByUserId(userId).getBalance());
        return JsonUtil.toJson(dto);
    }

    private long sum(List<Leg> legs, String bizType) {
        return legs.stream().filter(l -> l.bizType().equals(bizType)).mapToLong(Leg::amount).sum();
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
        dto.setStatus(entry.getStatus());
        dto.setPpReceipt(entry.getPpReceipt());
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

    private static String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }
}