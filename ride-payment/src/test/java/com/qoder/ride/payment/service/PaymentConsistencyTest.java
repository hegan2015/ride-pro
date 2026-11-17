package com.qoder.ride.payment.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.facade.payment.RefundRequest;
import com.qoder.ride.facade.payment.RefundSummaryDTO;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.facade.payment.TransferRequest;
import com.qoder.ride.facade.payment.WalletAuditDTO;
import com.qoder.ride.facade.payment.WalletDTO;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.WithdrawOrderDO;
import com.qoder.ride.payment.mapper.IdempotentMapper;
import com.qoder.ride.payment.mapper.LedgerMapper;
import com.qoder.ride.payment.mapper.PayOutboxMapper;
import com.qoder.ride.payment.mapper.RefundOutboxMapper;
import com.qoder.ride.payment.mapper.WalletMapper;
import com.qoder.ride.payment.mapper.WithdrawOrderMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 支付一致性：幂等并发只扣一次、余额守卫、退款兜底、账面守恒（H2 内存库 + 真实事务）。 */
class PaymentConsistencyTest {

    private static PaymentService service;
    private static WalletMapper walletMapper;
    private static LedgerMapper ledgerMapper;
    private static PayOutboxMapper outboxMapper;
    private static WithdrawOrderMapper withdrawOrderMapper;
    private static PaymentProperties properties;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeAll
    static void setUp() throws Exception {
        UnpooledDataSource ds = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:pay_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        ScriptRunner runner = new ScriptRunner(ds.getConnection());
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
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        walletMapper = template.getMapper(WalletMapper.class);
        ledgerMapper = template.getMapper(LedgerMapper.class);
        outboxMapper = template.getMapper(PayOutboxMapper.class);
        withdrawOrderMapper = template.getMapper(WithdrawOrderMapper.class);
        IdempotentMapper idempotentMapper = template.getMapper(IdempotentMapper.class);
        RefundOutboxMapper refundOutboxMapper = template.getMapper(RefundOutboxMapper.class);
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));
        properties = new PaymentProperties();
        service = new PaymentService(walletMapper, ledgerMapper, idempotentMapper, outboxMapper,
                refundOutboxMapper, withdrawOrderMapper, tt, properties);
    }

    /** 模拟 WithdrawProcessor：受理→处理中→终态（测试里立即结算，不等渠道模拟时长）。 */
    private void settleWithdraws(String userId) {
        long now = System.currentTimeMillis();
        for (WithdrawOrderDO row : withdrawOrderMapper.selectByStatus("ACCEPTED", 100)) {
            if (!row.getUserId().equals(userId)) {
                continue;
            }
            withdrawOrderMapper.casStatus(row.getId(), "ACCEPTED", "PROCESSING", now);
            service.settleWithdraw(row);
        }
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

    @Test
    void concurrentPayDebitsExactlyOnce() throws Exception {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 10000);
        long platformBefore = walletMapper.selectByUserId("SYS_PLATFORM") == null
                ? 0 : walletMapper.selectByUserId("SYS_PLATFORM").getBalance();
        long outboxBefore = outboxMapper.countByStatus("NEW") + outboxMapper.countByStatus("SENT");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    service.pay(payRequest(orderId, passenger, driver, 3000));
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                    // 并发冲突方允许失败， invariant 是只扣一次
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertTrue(ok.get() >= 1);
        assertEquals(7000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(2400, walletMapper.selectByUserId(driver).getBalance());
        assertEquals(platformBefore + 600, walletMapper.selectByUserId("SYS_PLATFORM").getBalance());
        assertEquals(1, ledgerMapper.countPayDebit(orderId));
        assertTrue(service.isPaid(orderId));
        assertEquals(outboxBefore + 1, outboxMapper.countByStatus("NEW") + outboxMapper.countByStatus("SENT"));
        // 再次支付走幂等回放
        PayResultDTO repeated = service.pay(payRequest(orderId, passenger, driver, 3000));
        assertTrue(repeated.getRepeated());
        assertEquals(7000, walletMapper.selectByUserId(passenger).getBalance());
    }

    @Test
    void payInsufficientBalanceRejected() {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 100);
        BizException e = assertThrows(BizException.class,
                () -> service.pay(payRequest(orderId, passenger, driver, 3000)));
        assertTrue(e.getMessage().contains("余额不足"));
        assertEquals(100, walletMapper.selectByUserId(passenger).getBalance());
        assertFalse(service.isPaid(orderId));
    }

    @Test
    void transferIdempotentAndGuarded() {
        String from = uid("P"), to = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(from, 1000);
        TransferRequest r = new TransferRequest();
        r.setFromUserId(from);
        r.setToUserId(to);
        r.setAmount(300L);
        r.setOrderId(orderId);
        r.setRequestId("CANCEL-" + orderId);
        r.setBizType("CANCEL_FEE");
        service.transfer(r);
        PayResultDTO again = service.transfer(r);
        assertTrue(again.getRepeated());
        assertEquals(700, walletMapper.selectByUserId(from).getBalance());
        assertEquals(300, walletMapper.selectByUserId(to).getBalance());
        r.setAmount(9999L);
        r.setRequestId("CANCEL-OTHER-" + orderId);
        assertThrows(BizException.class, () -> service.transfer(r));
        assertEquals(700, walletMapper.selectByUserId(from).getBalance());
    }

    @Test
    void refundPlatformCoversDriverShortfall() {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 10000);
        service.pay(payRequest(orderId, passenger, driver, 3000));
        assertEquals(7000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(2400, walletMapper.selectByUserId(driver).getBalance());
        topup("SYS_PLATFORM", 10000);
        long platformBefore = walletMapper.selectByUserId("SYS_PLATFORM").getBalance();

        // 司机把收入全部提现（冻结→结算扣款），退款时可用余额不足 → 平台兜底，乘客仍全额到账
        TopupRequest w = new TopupRequest();
        w.setUserId(driver);
        w.setAmount(2400L);
        w.setBizNo("WD-" + driver);
        service.withdraw(w);
        settleWithdraws(driver);
        assertEquals(0, walletMapper.selectByUserId(driver).getBalance());

        RefundRequest refund = new RefundRequest();
        refund.setOrderId(orderId);
        refund.setPassengerId(passenger);
        refund.setDriverId(driver);
        refund.setAmount(3000L);
        refund.setRequestId("REFUND-" + orderId);
        PayResultDTO result = service.refund(refund);
        assertEquals(0, result.getDriverIncome());
        assertEquals(3000, result.getFee());
        assertEquals(10000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(0, walletMapper.selectByUserId(driver).getBalance());
        assertEquals(platformBefore - 3000, walletMapper.selectByUserId("SYS_PLATFORM").getBalance());
        assertTrue(service.isRefunded(orderId));

        PayResultDTO again = service.refund(refund);
        assertTrue(again.getRepeated());
        assertEquals(10000, walletMapper.selectByUserId(passenger).getBalance());
    }

    @Test
    void refundNormalSplit() {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 10000);
        service.pay(payRequest(orderId, passenger, driver, 3000));
        RefundRequest refund = new RefundRequest();
        refund.setOrderId(orderId);
        refund.setPassengerId(passenger);
        refund.setDriverId(driver);
        refund.setAmount(3000L);
        refund.setRequestId("REFUND-" + orderId);
        PayResultDTO result = service.refund(refund);
        assertEquals(2400, result.getDriverIncome());
        assertEquals(600, result.getFee());
        assertEquals(10000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(0, walletMapper.selectByUserId(driver).getBalance());
    }

    @Test
    void withdrawFreezeSettleAndIdempotency() {
        String user = uid("D");
        topup(user, 500);
        TopupRequest w = new TopupRequest();
        w.setUserId(user);
        w.setAmount(500L);
        w.setBizNo("WD-" + user);
        WalletDTO accepted = service.withdraw(w);
        // 受理只冻结：余额不变，可用为 0
        assertEquals(500L, accepted.getBalance().longValue());
        assertEquals(500L, accepted.getFrozenAmount().longValue());
        assertEquals(0L, accepted.getAvailableBalance().longValue());
        // 重复受理幂等回放；冻结期间再提现被可用余额守卫拒绝
        assertEquals(500L, service.withdraw(w).getFrozenAmount().longValue());
        TopupRequest w2 = new TopupRequest();
        w2.setUserId(user);
        w2.setAmount(1L);
        w2.setBizNo("WD2-" + user);
        assertThrows(BizException.class, () -> service.withdraw(w2));
        settleWithdraws(user);
        assertEquals(0, walletMapper.selectByUserId(user).getBalance());
        assertEquals(0, walletMapper.selectByUserId(user).getFrozenAmount());
        // 终态后幂等回放返回最新钱包视图
        assertEquals(0L, service.withdraw(w).getBalance().longValue());
        assertEquals("SUCCESS", service.withdrawOrders(user).get(0).getStatus());
    }

    @Test
    void withdrawFailureUnfreezesAndConserves() {
        String user = uid("D");
        topup(user, 800);
        properties.setWithdrawFailBps(10000);
        try {
            TopupRequest w = new TopupRequest();
            w.setUserId(user);
            w.setAmount(800L);
            w.setBizNo("WDFAIL-" + user);
            service.withdraw(w);
            settleWithdraws(user);
        } finally {
            properties.setWithdrawFailBps(0);
        }
        assertEquals(800, walletMapper.selectByUserId(user).getBalance());
        assertEquals(0, walletMapper.selectByUserId(user).getFrozenAmount());
        assertEquals("FAILED", service.withdrawOrders(user).get(0).getStatus());
        // 失败解冻不动余额，账面守恒；失败单不占日累计额度，可立即重试
        for (WalletAuditDTO a : service.audit()) {
            assertTrue(a.getOk(), "账户不平: " + a.getUserId());
        }
        TopupRequest retry = new TopupRequest();
        retry.setUserId(user);
        retry.setAmount(800L);
        retry.setBizNo("WDRETRY-" + user);
        service.withdraw(retry);
        settleWithdraws(user);
        assertEquals(0, walletMapper.selectByUserId(user).getBalance());
    }

    @Test
    void refundWithoutPaymentRejected() {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        RefundRequest refund = new RefundRequest();
        refund.setOrderId(orderId);
        refund.setPassengerId(passenger);
        refund.setDriverId(driver);
        refund.setAmount(100L);
        refund.setRequestId("REFUND-" + orderId + "-1");
        BizException e = assertThrows(BizException.class, () -> service.refund(refund));
        assertTrue(e.getMessage().contains("无支付流水"));
    }

    @Test
    void partialRefundCumulativeGuard() {
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 10000);
        service.pay(payRequest(orderId, passenger, driver, 3000));
        long platformBefore = walletMapper.selectByUserId("SYS_PLATFORM").getBalance();

        RefundRequest r1 = new RefundRequest();
        r1.setOrderId(orderId);
        r1.setPassengerId(passenger);
        r1.setDriverId(driver);
        r1.setAmount(1000L);
        r1.setRequestId("REFUND-" + orderId + "-1");
        PayResultDTO first = service.refund(r1);
        // 手续费按比例退：600 * 1000/3000 = 200，司机回吐 800
        assertEquals(200, first.getFee());
        assertEquals(800, first.getDriverIncome());
        assertEquals(8000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(1600, walletMapper.selectByUserId(driver).getBalance());

        // 累计守卫：剩余可退 2000，超退拒绝且不动账
        RefundRequest over = new RefundRequest();
        over.setOrderId(orderId);
        over.setPassengerId(passenger);
        over.setDriverId(driver);
        over.setAmount(2500L);
        over.setRequestId("REFUND-" + orderId + "-2");
        BizException e = assertThrows(BizException.class, () -> service.refund(over));
        assertTrue(e.getMessage().contains("可退余额"));
        assertEquals(8000, walletMapper.selectByUserId(passenger).getBalance());

        // 同键重放幂等；补齐剩余 2000 后累计=实付
        assertTrue(service.refund(r1).getRepeated());
        RefundRequest r2 = new RefundRequest();
        r2.setOrderId(orderId);
        r2.setPassengerId(passenger);
        r2.setDriverId(driver);
        r2.setAmount(2000L);
        r2.setRequestId("REFUND-" + orderId + "-2");
        service.refund(r2);
        assertEquals(10000, walletMapper.selectByUserId(passenger).getBalance());
        assertEquals(0, walletMapper.selectByUserId(driver).getBalance());
        // 手续费 600 随退款全额退回平台出资部分，平台净回到支付前水平
        assertEquals(platformBefore - 600, walletMapper.selectByUserId("SYS_PLATFORM").getBalance());
        RefundSummaryDTO summary = service.refundSummary(orderId);
        assertEquals(3000, summary.getTotalAmount());
        assertEquals(2, summary.getCount());
        assertTrue(service.isRefunded(orderId));
        for (WalletAuditDTO a : service.audit()) {
            assertTrue(a.getOk(), "账户不平: " + a.getUserId());
        }
    }

    @Test
    void riskLimitsAndRequiredIdempotencyKey() {
        String user = uid("P");
        TopupRequest noKey = new TopupRequest();
        noKey.setUserId(user);
        noKey.setAmount(100L);
        BizException e = assertThrows(BizException.class, () -> service.topup(noKey));
        assertTrue(e.getMessage().contains("必填"));

        TopupRequest overLimit = new TopupRequest();
        overLimit.setUserId(user);
        overLimit.setAmount(1_000_001L);
        overLimit.setBizNo("TOPUP-OVER-" + user);
        assertTrue(assertThrows(BizException.class, () -> service.topup(overLimit)).getMessage().contains("单笔限额"));

        topup(user, 600_000);
        TopupRequest w = new TopupRequest();
        w.setUserId(user);
        w.setAmount(500_001L);
        w.setBizNo("WD-OVER-" + user);
        assertTrue(assertThrows(BizException.class, () -> service.withdraw(w)).getMessage().contains("单笔限额"));
        assertEquals(600_000, walletMapper.selectByUserId(user).getBalance());
    }

    @Test
    void walletReadOnlyDoesNotCreate() {
        String ghost = uid("GHOST");
        assertEquals(0, service.wallet(ghost).getBalance());
        org.junit.jupiter.api.Assertions.assertNull(walletMapper.selectByUserId(ghost));
    }

    @Test
    void auditAlwaysConserved() {
        // 制造一批动账后全量核对：钱包余额 == 流水回放求和
        String passenger = uid("P"), driver = uid("D"), orderId = "O" + SEQ.incrementAndGet();
        topup(passenger, 20000);
        service.pay(payRequest(orderId, passenger, driver, 5000));
        TransferRequest t = new TransferRequest();
        t.setFromUserId(passenger);
        t.setToUserId(driver);
        t.setAmount(300L);
        t.setOrderId(orderId);
        t.setRequestId("CANCEL-" + orderId);
        t.setBizType("CANCEL_FEE");
        service.transfer(t);
        List<WalletAuditDTO> audit = service.audit();
        assertFalse(audit.isEmpty());
        for (WalletAuditDTO a : audit) {
            assertTrue(a.getOk(), "账户不平: " + a.getUserId() + " diff=" + a.getDiff());
            assertEquals(0, a.getDiff());
        }
    }
}