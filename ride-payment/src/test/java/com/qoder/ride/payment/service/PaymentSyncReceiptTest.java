package com.qoder.ride.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.facade.payment.RefundRequest;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.payment.client.PaymentProClient;
import com.qoder.ride.payment.client.PaymentProIntegration;
import com.qoder.ride.payment.client.PaymentProRejectedException;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import com.qoder.ride.payment.dataobject.WithdrawOrderDO;
import com.qoder.ride.payment.mapper.IdempotentMapper;
import com.qoder.ride.payment.mapper.LedgerMapper;
import com.qoder.ride.payment.mapper.MirrorOutboxMapper;
import com.qoder.ride.payment.mapper.PayOutboxMapper;
import com.qoder.ride.payment.mapper.PaymentProAccountMapper;
import com.qoder.ride.payment.mapper.RefundOutboxMapper;
import com.qoder.ride.payment.mapper.WalletMapper;
import com.qoder.ride.payment.mapper.WithdrawOrderMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.6.0 同步回执流程：先落 PENDING 流水→同步调用 payment-pro→回执确认动账；
 * 明确拒绝终态失败退回；通信异常 PENDING 由恢复任务续传确认。
 */
class PaymentSyncReceiptTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private PaymentService service;
    private MirrorDispatcher dispatcher;
    private WalletMapper walletMapper;
    private LedgerMapper ledgerMapper;
    private MirrorOutboxMapper mirrorMapper;
    private IdempotentMapper idempotentMapper;
    private PayOutboxMapper outboxMapper;
    private WithdrawOrderMapper withdrawOrderMapper;
    private PaymentProClient client;

    @BeforeEach
    void setUp() throws Exception {
        String db = "sync_test_" + SEQ.incrementAndGet();
        UnpooledDataSource ds = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:" + db + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        org.apache.ibatis.jdbc.ScriptRunner runner = new org.apache.ibatis.jdbc.ScriptRunner(ds.getConnection());
        runner.setLogWriter(null);
        runner.setStopOnError(true);
        runner.runScript(new InputStreamReader(Resources.getResourceAsStream("schema.sql"), StandardCharsets.UTF_8));
        Configuration cfg = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(WalletMapper.class);
        cfg.addMapper(LedgerMapper.class);
        cfg.addMapper(IdempotentMapper.class);
        cfg.addMapper(PayOutboxMapper.class);
        cfg.addMapper(RefundOutboxMapper.class);
        cfg.addMapper(WithdrawOrderMapper.class);
        cfg.addMapper(MirrorOutboxMapper.class);
        cfg.addMapper(PaymentProAccountMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        walletMapper = template.getMapper(WalletMapper.class);
        ledgerMapper = template.getMapper(LedgerMapper.class);
        idempotentMapper = template.getMapper(IdempotentMapper.class);
        outboxMapper = template.getMapper(PayOutboxMapper.class);
        mirrorMapper = template.getMapper(MirrorOutboxMapper.class);
        withdrawOrderMapper = template.getMapper(WithdrawOrderMapper.class);
        RefundOutboxMapper refundOutboxMapper = template.getMapper(RefundOutboxMapper.class);
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PaymentProperties properties = new PaymentProperties();

        client = mock(PaymentProClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.getMerchantMemberId()).thenReturn("M1");
        when(client.getMerchantAccountId()).thenReturn("A1");
        when(client.getCallbackBaseUrl()).thenReturn("http://127.0.0.1:8090");
        when(client.balance(anyString())).thenReturn(0L);
        when(client.accountOpen(anyString(), anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return JSON.createObjectNode().put("memberId", "M-" + name).put("accountId", "A-" + name);
        });
        when(client.topup(anyString(), anyLong(), anyString())).thenReturn("E1");
        when(client.tradeCreate(anyString(), anyLong(), anyString())).thenReturn("O1");
        when(client.tradePay(anyString(), anyString(), anyString(), anyString())).thenReturn("P1");
        when(client.withdraw(anyString(), anyLong(), anyString())).thenReturn("P-WD");

        PaymentProIntegration integration = new PaymentProIntegration(client,
                template.getMapper(PaymentProAccountMapper.class), properties);
        MirrorExecutor executor = new MirrorExecutor(mirrorMapper, ledgerMapper, integration, client, properties);
        service = new PaymentService(walletMapper, ledgerMapper, idempotentMapper, outboxMapper,
                refundOutboxMapper, withdrawOrderMapper, tt, properties, mirrorMapper, integration, executor);
        dispatcher = new MirrorDispatcher(mirrorMapper, executor, service, integration, properties);
    }

    private String uid(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    private void topup(String userId, long amount) {
        TopupRequest r = new TopupRequest();
        r.setUserId(userId);
        r.setAmount(amount);
        r.setBizNo("TOPUP-" + userId + "-" + SEQ.incrementAndGet());
        service.topup(r);
    }

    private PayRequest payRequest(String orderId, String passenger, String driver, long amount) {
        PayRequest r = new PayRequest();
        r.setOrderId(orderId);
        r.setPassengerId(passenger);
        r.setDriverId(driver);
        r.setAmount(amount);
        r.setRequestId("PAY-" + orderId);
        return r;
    }

    private List<LedgerEntryDO> legs(String refKey) {
        return ledgerMapper.selectByRequestId(refKey);
    }

    @Test
    void topupReceiptConfirmsLedgerAndWallet() {
        String u = uid("U");
        TopupRequest r = new TopupRequest();
        r.setUserId(u);
        r.setAmount(5000L);
        r.setBizNo("TOPUP-SYNC-1-" + u);
        service.topup(r);

        assertEquals(5000L, walletMapper.selectByUserId(u).getBalance());
        LedgerEntryDO leg = legs(r.getBizNo()).get(0);
        assertEquals("CONFIRMED", leg.getStatus());
        assertEquals(5000L, leg.getBalanceAfter());
        assertNotNull(leg.getPpReceipt());
        assertEquals("SENT", mirrorMapper.selectByBizNo("M-TOPUP-" + r.getBizNo()).getStatus());
        assertNotNull(idempotentMapper.selectByRequestId(r.getBizNo()).getResultJson());
    }

    @Test
    void topupRejectionFailsTerminalAndRetryable() {
        String u = uid("U");
        TopupRequest r = new TopupRequest();
        r.setUserId(u);
        r.setAmount(5000L);
        r.setBizNo("TOPUP-SYNC-2-" + u);
        when(client.topup(anyString(), anyLong(), eq(r.getBizNo())))
                .thenThrow(new PaymentProRejectedException("HTTP 400 {\"error\":\"余额不足\"}"));

        assertThrows(BizException.class, () -> service.topup(r));
        assertEquals(0L, walletMapper.selectByUserId(u).getBalance());
        assertEquals("FAILED", legs(r.getBizNo()).get(0).getStatus());
        assertEquals("FAILED", mirrorMapper.selectByBizNo("M-TOPUP-" + r.getBizNo()).getStatus());
        assertNull(idempotentMapper.selectByRequestId(r.getBizNo()));

        org.mockito.Mockito.doReturn("E2").when(client).topup(anyString(), anyLong(), eq(r.getBizNo()));
        service.topup(r);
        assertEquals(5000L, walletMapper.selectByUserId(u).getBalance());
        assertTrue(legs(r.getBizNo()).stream().anyMatch(l -> "CONFIRMED".equals(l.getStatus())));
    }

    @Test
    void payReceiptConfirmsThreeLegsAndOutbox() {
        String p = uid("P");
        String d = uid("D");
        topup(p, 10000);
        String orderId = "O" + SEQ.incrementAndGet();
        PayResultDTO result = service.pay(payRequest(orderId, p, d, 1704));

        assertEquals(10000 - 1704L, walletMapper.selectByUserId(p).getBalance());
        assertEquals(1704 - result.getFee(), walletMapper.selectByUserId(d).getBalance());
        List<LedgerEntryDO> payLegs = legs("PAY-" + orderId);
        assertEquals(3, payLegs.size());
        assertTrue(payLegs.stream().allMatch(l -> "CONFIRMED".equals(l.getStatus())));
        assertTrue(payLegs.stream().allMatch(l -> "P1".equals(l.getPpReceipt())));
        MirrorOutboxDO mirror = mirrorMapper.selectByBizNo("M-PAY-PAY-" + orderId);
        assertEquals("SENT", mirror.getStatus());
        assertEquals("O1", mirror.getPpOrderId());
        assertEquals("P1", mirror.getPpPaymentId());
        assertEquals(1, outboxMapper.countByStatus("NEW"));
        assertTrue(service.isPaid(orderId));
    }

    @Test
    void payCommunicationFailureLeavesPendingAndDispatcherRecovers() {
        String p = uid("P");
        String d = uid("D");
        topup(p, 10000);
        String orderId = "O" + SEQ.incrementAndGet();
        when(client.tradeCreate(anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("payment-pro trade.create 调用异常: connect timeout"));

        BizException e = assertThrows(BizException.class, () -> service.pay(payRequest(orderId, p, d, 1704)));
        assertTrue(e.getMessage().contains("PENDING"));
        assertEquals(10000L, walletMapper.selectByUserId(p).getBalance());
        assertEquals(1704L, walletMapper.selectByUserId(p).getFrozenAmount());
        assertTrue(legs("PAY-" + orderId).stream().allMatch(l -> "PENDING".equals(l.getStatus())));
        assertTrue(!service.isPaid(orderId));

        org.mockito.Mockito.doReturn("O1").when(client).tradeCreate(anyString(), anyLong(), anyString());
        MirrorOutboxDO mirror = mirrorMapper.selectByBizNo("M-PAY-PAY-" + orderId);
        mirrorMapper.markRetry(mirror.getBizNo(), 0L, null);
        dispatcher.deliver();

        assertEquals(10000 - 1704L, walletMapper.selectByUserId(p).getBalance());
        assertEquals(0L, walletMapper.selectByUserId(p).getFrozenAmount());
        assertTrue(legs("PAY-" + orderId).stream().allMatch(l -> "CONFIRMED".equals(l.getStatus())));
        assertEquals("SENT", mirrorMapper.selectByBizNo("M-PAY-PAY-" + orderId).getStatus());
        assertTrue(service.isPaid(orderId));
        assertEquals(1, outboxMapper.countByStatus("NEW"));
    }

    @Test
    void withdrawRejectionFailsOrderAndUnfreezes() {
        String u = uid("U");
        topup(u, 10000);
        TopupRequest w = new TopupRequest();
        w.setUserId(u);
        w.setAmount(9000L);
        w.setBizNo("WD-SYNC-" + u);
        service.withdraw(w);
        org.mockito.Mockito.doThrow(new PaymentProRejectedException("HTTP 400 {\"error\":\"可用余额不足\"}"))
                .when(client).withdraw(anyString(), anyLong(), eq(w.getBizNo()));

        WithdrawOrderDO row = withdrawOrderMapper.selectByBizNo(w.getBizNo());
        withdrawOrderMapper.casStatus(row.getId(), "ACCEPTED", "PROCESSING", System.currentTimeMillis());
        service.settleWithdraw(row);

        assertEquals("FAILED", withdrawOrderMapper.selectByBizNo(w.getBizNo()).getStatus());
        assertEquals(10000L, walletMapper.selectByUserId(u).getBalance());
        assertEquals(0L, walletMapper.selectByUserId(u).getFrozenAmount());
        assertEquals("FAILED", legs(w.getBizNo()).get(0).getStatus());
        assertEquals("FAILED", mirrorMapper.selectByBizNo("M-WITHDRAW-" + w.getBizNo()).getStatus());
    }

    @Test
    void withdrawReceiptConfirmsSuccess() {
        String u = uid("U");
        topup(u, 10000);
        TopupRequest w = new TopupRequest();
        w.setUserId(u);
        w.setAmount(3000L);
        w.setBizNo("WD-SYNC-OK-" + u);
        service.withdraw(w);

        WithdrawOrderDO row = withdrawOrderMapper.selectByBizNo(w.getBizNo());
        withdrawOrderMapper.casStatus(row.getId(), "ACCEPTED", "PROCESSING", System.currentTimeMillis());
        service.settleWithdraw(row);

        assertEquals("SUCCESS", withdrawOrderMapper.selectByBizNo(w.getBizNo()).getStatus());
        assertEquals(7000L, walletMapper.selectByUserId(u).getBalance());
        assertEquals(0L, walletMapper.selectByUserId(u).getFrozenAmount());
        LedgerEntryDO leg = legs(w.getBizNo()).get(0);
        assertEquals("CONFIRMED", leg.getStatus());
        assertEquals(7000L, leg.getBalanceAfter());
        assertEquals("P-WD", leg.getPpReceipt());
    }

    @Test
    void refundReceiptConfirmsClawbackAndPassengerCredit() {
        String p = uid("P");
        String d = uid("D");
        topup(p, 10000);
        String orderId = "O" + SEQ.incrementAndGet();
        service.pay(payRequest(orderId, p, d, 2000));

        RefundRequest r = new RefundRequest();
        r.setOrderId(orderId);
        r.setPassengerId(p);
        r.setDriverId(d);
        r.setAmount(800L);
        r.setRequestId("REFUND-" + orderId + "-1");
        PayResultDTO result = service.refund(r);

        assertEquals(800L, result.getAmount());
        assertTrue(service.isRefunded(orderId));
        List<LedgerEntryDO> refundLegs = legs(r.getRequestId());
        assertTrue(refundLegs.stream().allMatch(l -> "CONFIRMED".equals(l.getStatus())));
        assertEquals("SENT", mirrorMapper.selectByBizNo("M-REFUND-" + r.getRequestId()).getStatus());
        assertEquals(8800L, walletMapper.selectByUserId(p).getBalance());
    }
}