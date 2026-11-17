package com.qoder.ride.payment.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.payment.LedgerEntryDTO;
import com.qoder.ride.facade.payment.LedgerQuery;
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
import com.qoder.ride.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService paymentService;

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
}