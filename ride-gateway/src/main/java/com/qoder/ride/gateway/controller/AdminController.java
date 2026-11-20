package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.driver.DriverCreateRequest;
import com.qoder.ride.facade.driver.DriverDTO;
import com.qoder.ride.facade.driver.DriverFacade;
import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.location.LocationReportRequest;
import com.qoder.ride.facade.order.OrderFacade;
import com.qoder.ride.facade.passenger.PassengerCreateRequest;
import com.qoder.ride.facade.passenger.PassengerDTO;
import com.qoder.ride.facade.passenger.PassengerFacade;
import com.qoder.ride.facade.payment.LedgerCorrectionRequest;
import com.qoder.ride.facade.payment.LedgerQuery;
import com.qoder.ride.facade.payment.PaymentFacade;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.facade.push.PushFacade;
import com.qoder.ride.gateway.web.GatewaySupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 管理端：仪表盘/资金流水/订单处置/钱包核对/系统日志/一致性监控 + 演示数据播种（只增不删）。 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Set<String> LOG_FILES = Set.of(
            "registry", "passenger", "driver", "location", "pricing",
            "order", "dispatch", "payment", "push", "gateway");
    private static final long LOG_TAIL_BYTES = 512 * 1024;

    @DubboReference
    private OrderFacade orderFacade;
    @DubboReference
    private PaymentFacade paymentFacade;
    @DubboReference
    private PushFacade pushFacade;
    @DubboReference
    private PassengerFacade passengerFacade;
    @DubboReference
    private DriverFacade driverFacade;
    @DubboReference
    private LocationFacade locationFacade;

    @GetMapping("/overview")
    public ResponseEntity<Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orders", orderFacade.stats().orThrow());
        data.put("payment", paymentFacade.stats().orThrow());
        data.put("outbox", paymentFacade.outboxStats().orThrow());
        data.put("onlineConnections", pushFacade.onlineCount().orThrow());
        List<com.qoder.ride.facade.payment.WalletAuditDTO> audit = paymentFacade.audit().orThrow();
        data.put("auditOk", audit.stream().allMatch(a -> Boolean.TRUE.equals(a.getOk())));
        return ResponseEntity.ok(data);
    }

    @GetMapping("/ledger")
    public ResponseEntity<Object> ledger(@RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String orderId,
                                         @RequestParam(required = false) String bizType,
                                         @RequestParam(required = false) Long from,
                                         @RequestParam(required = false) Long to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        LedgerQuery query = new LedgerQuery();
        query.setUserId(userId);
        query.setOrderId(orderId);
        query.setBizType(bizType);
        query.setFrom(from);
        query.setTo(to);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(paymentFacade.ledger(query));
    }

    @GetMapping("/wallets")
    public ResponseEntity<Object> wallets() {
        return GatewaySupport.of(paymentFacade.wallets());
    }

    @GetMapping("/audit")
    public ResponseEntity<Object> audit() {
        return GatewaySupport.of(paymentFacade.audit());
    }

    @GetMapping("/outbox")
    public ResponseEntity<Object> outbox() {
        return GatewaySupport.of(paymentFacade.outboxStats());
    }

    @GetMapping("/push/connections")
    public ResponseEntity<Object> connections() {
        return GatewaySupport.of(pushFacade.connections());
    }

    @GetMapping("/push/events")
    public ResponseEntity<Object> pushEvents(@RequestParam(defaultValue = "100") int limit) {
        return GatewaySupport.of(pushFacade.recentEvents(limit));
    }

    @PostMapping("/orders/{id}/refund")
    public ResponseEntity<Object> refund(@PathVariable String id,
                                         @RequestParam(required = false) String reason,
                                         @RequestParam(required = false) Long amount) {
        log.warn("管理端退款请求 orderId={} reason={} amount={}", id, reason, amount);
        return GatewaySupport.of(orderFacade.refund(id, reason == null ? "管理端退款" : reason, amount));
    }

    /** DEAD outbox 事件列表（重试耗尽待人工介入）。 */
    @GetMapping("/outbox/dead")
    public ResponseEntity<Object> deadOutbox() {
        return GatewaySupport.of(paymentFacade.deadOutbox());
    }

    /** 人工重放 DEAD 事件（orderId 或 requestId）。 */
    @PostMapping("/outbox/replay")
    public ResponseEntity<Object> replayOutbox(@RequestParam String key) {
        log.warn("管理端 outbox 重放 key={}", key);
        return GatewaySupport.of(paymentFacade.replayOutbox(key));
    }

    /** payment-pro 双账本镜像投递监控。 */
    @GetMapping("/mirror")
    public ResponseEntity<Object> mirrorStats() {
        return GatewaySupport.of(paymentFacade.mirrorStats());
    }

    /** 镜像 DEAD 事件列表。 */
    @GetMapping("/mirror/dead")
    public ResponseEntity<Object> deadMirrors() {
        return GatewaySupport.of(paymentFacade.deadMirrors());
    }

    /** 人工重放镜像 DEAD 事件（bizNo）。 */
    @PostMapping("/mirror/replay")
    public ResponseEntity<Object> replayMirror(@RequestParam String key) {
        log.warn("管理端镜像重放 key={}", key);
        return GatewaySupport.of(paymentFacade.replayMirror(key));
    }

    /** 人工关闭镜像 DEAD 事件（线下处理完毕后销账）。 */
    @PostMapping("/mirror/close")
    public ResponseEntity<Object> closeMirror(@RequestParam String key, @RequestParam String reason) {
        log.warn("管理端镜像关闭 key={} reason={}", key, reason);
        return GatewaySupport.of(paymentFacade.closeMirror(key, reason));
    }

    @PostMapping("/orders/{id}/force-cancel")
    public ResponseEntity<Object> forceCancel(@PathVariable String id, @RequestParam(required = false) String reason) {
        log.warn("管理端强制取消请求 orderId={} reason={}", id, reason);
        return GatewaySupport.of(orderFacade.forceCancel(id, reason == null ? "管理端强制取消" : reason));
    }

    /** 日志查看：白名单文件尾部 N 行；传 traceId 时跨全部服务日志串联检索。 */
    @GetMapping("/logs")
    public ResponseEntity<Object> logs(@RequestParam(defaultValue = "gateway") String file,
                                       @RequestParam(defaultValue = "200") int lines,
                                       @RequestParam(required = false) String traceId) throws IOException {
        int n = Math.max(1, Math.min(lines, 1000));
        Map<String, Object> data = new LinkedHashMap<>();
        if (traceId != null && !traceId.isBlank()) {
            if (!traceId.matches("[A-Za-z0-9_-]{1,64}")) {
                return ResponseEntity.badRequest().body(Map.of("error", "traceId 非法", "code", "A0400"));
            }
            List<Map<String, Object>> hits = new ArrayList<>();
            for (String name : LOG_FILES.stream().sorted().toList()) {
                for (String line : tail(Path.of("logs", name + ".log"), 1000)) {
                    if (line.contains(traceId)) {
                        hits.add(Map.of("file", name, "line", line));
                        if (hits.size() >= 500) {
                            break;
                        }
                    }
                }
            }
            data.put("traceId", traceId);
            data.put("hits", hits);
            return ResponseEntity.ok(data);
        }
        if (!LOG_FILES.contains(file)) {
            return ResponseEntity.badRequest().body(Map.of("error", "日志文件不在白名单", "code", "A0400"));
        }
        data.put("file", file);
        data.put("lines", tail(Path.of("logs", file + ".log"), n));
        return ResponseEntity.ok(data);
    }

    /**
     * 演示数据播种：按手机号去重只增不删，充值 bizNo 固定幂等（重复执行不重复加钱），
     * 绝不触碰既有订单/钱包/流水数据。
     */
    @PostMapping("/seed")
    public ResponseEntity<Object> seed() {
        List<PassengerDTO> passengers = passengerFacade.list().orThrow();
        List<DriverDTO> drivers = driverFacade.list().orThrow();
        int createdPassengers = 0;
        int createdDrivers = 0;
        List<String> seededUsers = new ArrayList<>();

        String[][] passengerSpecs = {{"演示乘客·小雨", "13900000001"}, {"演示乘客·小风", "13900000002"}};
        for (String[] spec : passengerSpecs) {
            PassengerDTO p = passengers.stream().filter(x -> spec[1].equals(x.getPhone())).findFirst().orElse(null);
            if (p == null) {
                PassengerCreateRequest req = new PassengerCreateRequest();
                req.setName(spec[0]);
                req.setPhone(spec[1]);
                p = passengerFacade.create(req).orThrow();
                createdPassengers++;
            }
            seededUsers.add(p.getId() + "|" + spec[1]);
        }

        String[][] driverSpecs = {
                {"演示司机·快车张", "13800000001", "京A·D0001", "比亚迪秦PLUS", "EXPRESS", "39.9087", "116.3975"},
                {"演示司机·专车李", "13800000002", "京A·D0002", "别克GL8", "PREMIER", "39.9150", "116.4040"},
                {"演示司机·出租王", "13800000003", "京B·T0003", "现代伊兰特", "TAXI", "39.9020", "116.3900"}};
        for (String[] spec : driverSpecs) {
            DriverDTO d = drivers.stream().filter(x -> spec[1].equals(x.getPhone())).findFirst().orElse(null);
            if (d == null) {
                DriverCreateRequest req = new DriverCreateRequest();
                req.setName(spec[0]);
                req.setPhone(spec[1]);
                req.setCarNo(spec[2]);
                req.setCarModel(spec[3]);
                req.setServiceType(spec[4]);
                d = driverFacade.create(req).orThrow();
                createdDrivers++;
            }
            if (!"ONLINE".equals(d.getStatus()) && !"BUSY".equals(d.getStatus())) {
                driverFacade.setOnline(d.getId(), true).orThrow();
            }
            LocationReportRequest report = new LocationReportRequest();
            report.setDriverId(d.getId());
            report.setLat(Double.parseDouble(spec[5]));
            report.setLng(Double.parseDouble(spec[6]));
            locationFacade.report(report).orThrow();
            seededUsers.add(d.getId() + "|" + spec[1]);
        }

        for (String entry : seededUsers) {
            String[] parts = entry.split("\\|");
            TopupRequest topup = new TopupRequest();
            topup.setUserId(parts[0]);
            topup.setAmount(20000L);
            topup.setBizNo("SEED-TOPUP-" + parts[1]);
            paymentFacade.topup(topup).orThrow();
        }
        log.info("演示数据播种完成 createdPassengers={} createdDrivers={}", createdPassengers, createdDrivers);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("createdPassengers", createdPassengers);
        data.put("createdDrivers", createdDrivers);
        data.put("topupPerUser", 20000);
        data.put("note", "只增不删：按手机号去重，充值幂等，重复执行不产生重复数据");
        return ResponseEntity.ok(data);
    }

    /**
     * 双账本历史冲正：本地多腿更正（借贷必相等），只增冲正腿不改原行，requestId 幂等。
     * 用于修复 CARD 误扣乘客钱包、pp 终态 FAILED 但本地已确认两类历史缺口。
     */
    @PostMapping("/ledger/correct")
    public ResponseEntity<Object> correctLedger(@RequestBody LedgerCorrectionRequest request) {
        log.warn("管理端双账本冲正 requestId={} orderId={} reason={}",
                request.getRequestId(), request.getOrderId(), request.getReason());
        return GatewaySupport.of(paymentFacade.correctLedger(request));
    }

    /** 双账本基线重算：仅镜像全局静默时允许；须先完成资金冲正，否则会掩盖真实缺口。 */
    @PostMapping("/dual-ledger/rebaseline")
    public ResponseEntity<Object> rebaselineDualLedger() {
        log.warn("管理端双账本基线重算");
        return GatewaySupport.of(paymentFacade.rebaselineDualLedger());
    }

    private List<String> tail(Path file, int lines) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        long size = Files.size(file);
        long skip = Math.max(0, size - LOG_TAIL_BYTES);
        try (InputStream in = Files.newInputStream(file)) {
            long skipped = in.skip(skip);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> all = new ArrayList<>(Arrays.asList(text.split("\n")));
            if (skipped > 0 && !all.isEmpty()) {
                all.remove(0);
            }
            int from = Math.max(0, all.size() - lines);
            return all.subList(from, all.size());
        }
    }
}