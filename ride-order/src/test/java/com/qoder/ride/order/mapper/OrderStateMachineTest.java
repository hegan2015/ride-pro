package com.qoder.ride.order.mapper;

import com.qoder.ride.order.dataobject.IdempotentRecordDO;
import com.qoder.ride.order.dataobject.OrderDO;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 订单状态机 CAS 与并发抢单（H2 内存库，与生产同 schema）。 */
class OrderStateMachineTest {

    private static SqlSessionFactory factory;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeAll
    static void setUp() throws Exception {
        UnpooledDataSource ds = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:order_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        ScriptRunner runner = new ScriptRunner(ds.getConnection());
        runner.setLogWriter(null);
        runner.setStopOnError(true);
        runner.runScript(new InputStreamReader(Resources.getResourceAsStream("schema.sql"), StandardCharsets.UTF_8));
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(OrderMapper.class);
        cfg.addMapper(IdempotentMapper.class);
        cfg.addMapper(RatingMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private OrderDO newOrder(String status) {
        long now = System.currentTimeMillis();
        OrderDO o = new OrderDO();
        o.setId("T" + SEQ.incrementAndGet());
        o.setPassengerId("P1");
        o.setPassengerName("测试乘客");
        o.setOriginLat(39.9);
        o.setOriginLng(116.4);
        o.setDestLat(39.95);
        o.setDestLng(116.45);
        o.setDistanceKm(6.0);
        o.setEstimateFee(2500L);
        o.setStatus(status);
        o.setRequestId("REQ-" + o.getId());
        o.setServiceType("EXPRESS");
        o.setCreatedAt(now);
        try (SqlSession s = factory.openSession(true)) {
            s.getMapper(OrderMapper.class).insert(o);
        }
        return o;
    }

    @Test
    void concurrentAcceptHasExactlyOneWinner() throws Exception {
        OrderDO order = newOrder("DISPATCHING");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String driverId = "D" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                try (SqlSession s = factory.openSession(true)) {
                    int rows = s.getMapper(OrderMapper.class).accept(order.getId(), driverId, "司机" + driverId, "京A·" + driverId,
                            System.currentTimeMillis());
                    if (rows == 1) {
                        wins.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(1, wins.get());
        try (SqlSession s = factory.openSession(true)) {
            OrderDO saved = s.getMapper(OrderMapper.class).selectById(order.getId());
            assertEquals("ACCEPTED", saved.getStatus());
            assertNotNull(saved.getDriverId());
        }
    }

    @Test
    void fullLifecycleCasChain() {
        OrderDO order = newOrder("DISPATCHING");
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            long now = System.currentTimeMillis();
            assertEquals(0, m.arrive(order.getId(), "D1", now));
            assertEquals(1, m.accept(order.getId(), "D1", "张师傅", "京A·1", now));
            assertEquals(0, m.accept(order.getId(), "D2", "李师傅", "京A·2", now));
            assertEquals(0, m.arrive(order.getId(), "D2", now));
            assertEquals(1, m.arrive(order.getId(), "D1", now));
            assertEquals(1, m.startTrip(order.getId(), "D1", now));
            assertEquals(0, m.cancel(order.getId(), "行程中不可取消", 0, now));
            assertEquals(1, m.completeTrip(order.getId(), "D1", 3000, 7.2, 18, "{\"totalFee\":3000}", now));
            assertEquals(0, m.pay("T-NOT-EXIST", now));
            assertEquals(1, m.pay(order.getId(), now));
            assertEquals(0, m.pay(order.getId(), now));
            OrderDO saved = m.selectById(order.getId());
            assertEquals("PAID", saved.getStatus());
            assertEquals(3000, saved.getActualFee());
            assertEquals(7.2, saved.getActualDistanceKm());
            assertEquals(18, saved.getActualDurationMin());
        }
    }

    @Test
    void cancelAndForceCancelGuards() {
        OrderDO a = newOrder("ARRIVED");
        OrderDO b = newOrder("IN_PROGRESS");
        OrderDO c = newOrder("PAID");
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            long now = System.currentTimeMillis();
            assertEquals(1, m.cancel(a.getId(), "乘客取消", 300, now));
            assertEquals(300, m.selectById(a.getId()).getCancelFee());
            assertEquals(0, m.cancel(b.getId(), "行程中", 0, now));
            assertEquals(1, m.forceCancel(b.getId(), "管理端强制取消", now));
            assertEquals(0, m.forceCancel(c.getId(), "已支付", now));
        }
    }

    @Test
    void driverCancelResetsToDispatching() {
        OrderDO o = newOrder("DISPATCHING");
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            long now = System.currentTimeMillis();
            assertEquals(1, m.accept(o.getId(), "D1", "张师傅", "京A·1", now));
            assertEquals(0, m.driverCancel(o.getId(), "D2", "非本司机", 5.0, "D2"));
            assertEquals(1, m.driverCancel(o.getId(), "D1", "司机取消", 5.0, "D1"));
            OrderDO saved = m.selectById(o.getId());
            assertEquals("DISPATCHING", saved.getStatus());
            assertNull(saved.getDriverId());
            assertNull(saved.getAcceptedAt());
            assertEquals("D1", saved.getDeclinedDriverIds());
        }
    }

    @Test
    void expireAndRefundGuards() {
        OrderDO dispatching = newOrder("DISPATCHING");
        OrderDO paid = newOrder("PAID");
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            long now = System.currentTimeMillis();
            assertEquals(0, m.expire(paid.getId(), now));
            assertEquals(1, m.expire(dispatching.getId(), now));
            assertEquals(0, m.refund(dispatching.getId(), "非已支付", now));
            assertEquals(1, m.refund(paid.getId(), "管理端退款", now));
            assertEquals(0, m.refund(paid.getId(), "重复退款", now));
            assertEquals("REFUNDED", m.selectById(paid.getId()).getStatus());
        }
    }

    @Test
    void markDispatchedIncrementsRoundOnlyWhenDispatching() {
        OrderDO o = newOrder("DISPATCHING");
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            long now = System.currentTimeMillis();
            assertEquals(1, m.markDispatched(o.getId(), now, 5.0));
            assertEquals(1, m.markDispatched(o.getId(), now, 8.0));
            assertEquals(2, m.selectById(o.getId()).getDispatchRound());
            m.expire(o.getId(), now);
            assertEquals(0, m.markDispatched(o.getId(), now, 11.0));
        }
    }

    @Test
    void idempotentRecordPrimaryKeyRejectsDuplicate() {
        String key = "PAY-T-IDEM-" + SEQ.incrementAndGet();
        long now = System.currentTimeMillis();
        try (SqlSession s = factory.openSession(true)) {
            IdempotentMapper m = s.getMapper(IdempotentMapper.class);
            m.insert(new IdempotentRecordDO(key, "PAY", "{}", now));
            assertThrows(Exception.class, () -> m.insert(new IdempotentRecordDO(key, "PAY", "{}", now)));
            assertNotNull(m.selectByRequestId(key));
        }
    }

    @Test
    void dynamicQueryFiltersAndPaginates() {
        String passenger = "PQ" + SEQ.incrementAndGet();
        for (int i = 0; i < 5; i++) {
            long now = System.currentTimeMillis();
            OrderDO o = new OrderDO();
            o.setId("T" + SEQ.incrementAndGet());
            o.setPassengerId(passenger);
            o.setOriginLat(39.9);
            o.setOriginLng(116.4);
            o.setDestLat(39.95);
            o.setDestLng(116.45);
            o.setDistanceKm(6.0);
            o.setEstimateFee(2500L);
            o.setStatus(i % 2 == 0 ? "PAID" : "CANCELLED");
            o.setRequestId("REQ-" + o.getId());
            o.setCreatedAt(now + i);
            try (SqlSession s = factory.openSession(true)) {
                s.getMapper(OrderMapper.class).insert(o);
            }
        }
        try (SqlSession s = factory.openSession(true)) {
            OrderMapper m = s.getMapper(OrderMapper.class);
            Map<String, Object> params = new HashMap<>();
            params.put("passengerId", passenger);
            params.put("status", "PAID");
            params.put("size", 2);
            params.put("offset", 0);
            assertEquals(3, m.count(params));
            List<OrderDO> page1 = m.query(params);
            assertEquals(2, page1.size());
            params.put("offset", 2);
            assertEquals(1, m.query(params).size());
            params.remove("status");
            assertEquals(5, m.count(params));
            params.put("from", System.currentTimeMillis() + 100000);
            assertEquals(0, m.count(params));
            assertTrue(m.query(new HashMap<>(Map.of("size", 10, "offset", 0))).size() >= 5);
        }
    }
}