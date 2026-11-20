package com.qoder.ride.payment.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.payment.CashierPayDTO;
import com.qoder.ride.facade.payment.CashierRequest;
import com.qoder.ride.facade.payment.LedgerCorrectionRequest;
import com.qoder.ride.facade.payment.LedgerEntryDTO;
import com.qoder.ride.facade.payment.LedgerQuery;
import com.qoder.ride.facade.payment.MirrorEventDTO;
import com.qoder.ride.facade.payment.OutboxEntryDTO;
import com.qoder.ride.facade.payment.OutboxStatsDTO;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.facade.payment.PaymentFacade;
import com.qoder.ride.facade.payment.PaymentStatsDTO;
import com.qoder.ride.facade.payment.RefundRequest;
import com.qoder.ride.facade.payment.RefundSummaryDTO;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.facade.payment.TransferRequest;
import com.qoder.ride.facade.payment.WalletAuditDTO;
import com.qoder.ride.facade.payment.WalletDTO;
import com.qoder.ride.facade.payment.WithdrawOrderDTO;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.PaymentProNotifyDO;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import com.qoder.ride.payment.mapper.PaymentProNotifyMapper;
import com.qoder.ride.payment.service.CashierService;
import com.qoder.ride.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;
    private final CashierService cashierService;
    private final com.qoder.ride.payment.service.PaymentMonitor paymentMonitor;
    private final PaymentProNotifyMapper notifyMapper;
    private final MirrorOutboxMapper mirrorMapper;
    private final PaymentProperties properties;

    @Override
    public Result<WalletDTO> topup(TopupRequest request) {
        return FacadeSupport.execute(() -> paymentService.topup(request));
    }

    @Override
    public Result<WalletDTO> wallet(String userId) {
        return FacadeSupport.execute(() -> paymentService.wallet(userId));
    }

    @Override
    public Result<List<WalletDTO>> wallets() {
        return FacadeSupport.execute(paymentService::wallets);
    }

    @Override
    public Result<WalletDTO> withdraw(TopupRequest request) {
        return FacadeSupport.execute(() -> paymentService.withdraw(request));
    }

    @Override
    public Result<List<WithdrawOrderDTO>> withdrawOrders(String userId) {
        return FacadeSupport.execute(() -> paymentService.withdrawOrders(userId));
    }

    @Override
    public Result<PayResultDTO> pay(PayRequest request) {
        return FacadeSupport.execute(() -> paymentService.pay(request));
    }

    @Override
    public Result<PayResultDTO> transfer(TransferRequest request) {
        return FacadeSupport.execute(() -> paymentService.transfer(request));
    }

    @Override
    public Result<PayResultDTO> refund(RefundRequest request) {
        return FacadeSupport.execute(() -> paymentService.refund(request));
    }

    @Override
    public Result<PayResultDTO> payResult(String orderId) {
        return FacadeSupport.execute(() -> paymentService.payResult(orderId));
    }

    @Override
    public Result<RefundSummaryDTO> refundSummary(String orderId) {
        return FacadeSupport.execute(() -> paymentService.refundSummary(orderId));
    }

    @Override
    public Result<Boolean> isPaid(String orderId) {
        return FacadeSupport.execute(() -> paymentService.isPaid(orderId));
    }

    @Override
    public Result<Boolean> isRefunded(String orderId) {
        return FacadeSupport.execute(() -> paymentService.isRefunded(orderId));
    }

    @Override
    public Result<PageDTO<LedgerEntryDTO>> ledger(LedgerQuery query) {
        return FacadeSupport.execute(() -> paymentService.ledger(query));
    }

    @Override
    public Result<List<WalletAuditDTO>> audit() {
        return FacadeSupport.execute(paymentService::audit);
    }

    @Override
    public Result<List<PaymentStatsDTO>> stats() {
        return FacadeSupport.execute(paymentService::stats);
    }

    @Override
    public Result<OutboxStatsDTO> outboxStats() {
        return FacadeSupport.execute(paymentService::outboxStats);
    }

    @Override
    public Result<List<OutboxEntryDTO>> deadOutbox() {
        return FacadeSupport.execute(paymentService::deadOutbox);
    }

    @Override
    public Result<Integer> replayOutbox(String key) {
        return FacadeSupport.execute(() -> paymentService.replayOutbox(key));
    }

    @Override
    public Result<Void> recordNotify(String event, String bizId, String payload) {
        return FacadeSupport.execute(() -> {
            try {
                notifyMapper.insert(new PaymentProNotifyDO(event, bizId, payload, System.currentTimeMillis()));
                log.info("payment-pro 通知留痕 event={} bizId={}", event, bizId);
            } catch (DuplicateKeyException e) {
                log.info("payment-pro 通知幂等命中 event={} bizId={}", event, bizId);
            }
            return null;
        });
    }

    @Override
    public Result<OutboxStatsDTO> mirrorStats() {
        return FacadeSupport.execute(() -> {
            OutboxStatsDTO stats = new OutboxStatsDTO();
            stats.setPendingCount((long) mirrorMapper.countPending());
            stats.setSentCount((long) mirrorMapper.countSent());
            stats.setDeadCount((long) mirrorMapper.countDead());
            stats.setMaxRetries(properties.getOutboxMaxRetries());
            Long oldest = mirrorMapper.oldestPendingCreatedAt();
            stats.setOldestPendingAgeMs(oldest == null ? 0L : System.currentTimeMillis() - oldest);
            return stats;
        });
    }

    @Override
    public Result<List<MirrorEventDTO>> deadMirrors() {
        return FacadeSupport.execute(() -> mirrorMapper.selectDead().stream().map(row -> {
            MirrorEventDTO dto = new MirrorEventDTO();
            dto.setBizNo(row.getBizNo());
            dto.setBizType(row.getBizType());
            dto.setRefKey(row.getRefKey());
            dto.setUserId(row.getUserId());
            dto.setOrderId(row.getOrderId());
            dto.setStatus(row.getStatus());
            dto.setRetries(row.getRetries());
            dto.setPpOrderId(row.getPpOrderId());
            dto.setPpPaymentId(row.getPpPaymentId());
            dto.setLastError(row.getLastError());
            dto.setCreatedAt(row.getCreatedAt());
            return dto;
        }).toList());
    }

    @Override
    public Result<Integer> replayMirror(String bizNo) {
        return FacadeSupport.execute(() -> {
            int count = mirrorMapper.replay(bizNo);
            if (count > 0) {
                log.warn("镜像 DEAD 事件人工重放 bizNo={}", bizNo);
            }
            return count;
        });
    }

    @Override
    public Result<Integer> closeMirror(String bizNo, String reason) {
        return FacadeSupport.execute(() -> paymentService.abandonMirror(bizNo, reason));
    }

    // ---------------- 收银台开放能力（additive，不改既有端点语义） ----------------

    @Override
    public Result<Map<String, Object>> cashierMethods(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.methods(request));
    }

    @Override
    public Result<Map<String, Object>> cashierCodeCreate(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.codeCreate(request));
    }

    @Override
    public Result<Map<String, Object>> cashierCodeParse(String code) {
        return FacadeSupport.execute(() -> cashierService.codeParse(code));
    }

    @Override
    public Result<Map<String, Object>> cashierCodeQuery(String code) {
        return FacadeSupport.execute(() -> cashierService.codeQuery(code));
    }

    @Override
    public Result<CashierPayDTO> cashierScanPay(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.scanPay(request));
    }

    @Override
    public Result<Map<String, Object>> cashierVerify(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.verify(request));
    }

    @Override
    public Result<CashierPayDTO> cashierPayVerified(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.payVerified(request));
    }

    @Override
    public Result<Boolean> cashierPayPasswordSet(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.payPasswordSet(request));
    }

    @Override
    public Result<Map<String, Object>> cashierSmsSend(CashierRequest request) {
        return FacadeSupport.execute(() -> cashierService.smsSend(request));
    }

    @Override
    public Result<Map<String, Object>> cashierReceipt(String orderId) {
        return FacadeSupport.execute(() -> cashierService.receipt(orderId));
    }

    @Override
    public Result<Map<String, Object>> correctLedger(LedgerCorrectionRequest request) {
        return FacadeSupport.execute(() -> paymentService.correctLedger(request));
    }

    @Override
    public Result<Map<String, Object>> rebaselineDualLedger() {
        return FacadeSupport.execute(paymentMonitor::rebaseline);
    }
}