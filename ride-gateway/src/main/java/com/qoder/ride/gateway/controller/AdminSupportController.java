package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.support.AdminUserDTO;
import com.qoder.ride.facade.support.CouponTemplateDTO;
import com.qoder.ride.facade.support.SupportFacade;
import com.qoder.ride.facade.passenger.PassengerFacade;
import com.qoder.ride.facade.driver.DriverFacade;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理端支撑：券模板/配置/RBAC/审计/证件审核/工单/安全事件/司乘封禁。受 AdminAuthFilter(/api/admin/**) 保护 + 角色拦截。 */
@RestController
@RequestMapping("/api/admin/support")
public class AdminSupportController {

    @DubboReference
    private SupportFacade supportFacade;
    @DubboReference
    private PassengerFacade passengerFacade;
    @DubboReference
    private DriverFacade driverFacade;
    @DubboReference
    private com.qoder.ride.facade.order.OrderFacade orderFacade;

    private String actor(String header) {
        return header == null || header.isBlank() ? "admin" : header;
    }

    /** 角色拦截：按 X-Admin-User 查角色；未知账号一律拒绝（默认 admin 为种子 ADMIN）。 */
    private boolean allowed(String user, String... roles) {
        try {
            AdminUserDTO a = supportFacade.adminByUsername(actor(user)).orThrow();
            if (a == null) return false;
            for (String r : roles) if (r.equals(a.getRole())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private ResponseEntity<Object> deny() {
        return ResponseEntity.status(403).body(Map.of("error", "当前角色无权执行该操作", "code", "A0301"));
    }

    @GetMapping("/templates")
    public ResponseEntity<Object> templates() {
        return GatewaySupport.of(supportFacade.listTemplates());
    }

    @PostMapping("/templates")
    public ResponseEntity<Object> createTemplate(@RequestBody CouponTemplateDTO t,
                                                 @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN", "OPS")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.createTemplate(t));
        supportFacade.audit(actor(user), "创建券模板", t.getName(), "type=" + t.getType() + " benefit=" + t.getBenefit());
        return resp;
    }

    @GetMapping("/coupons")
    public ResponseEntity<Object> coupons() {
        return GatewaySupport.of(supportFacade.listAllCoupons());
    }

    @GetMapping("/config")
    public ResponseEntity<Object> config() {
        Map<String, String> cfg = new LinkedHashMap<>(supportFacade.allConfig().orThrow());
        String key = cfg.get("support.csOpenKey");
        if (key != null && !key.isBlank()) cfg.put("support.csOpenKey", "******");
        return ResponseEntity.ok(cfg);
    }

    @PostMapping("/config")
    public ResponseEntity<Object> setConfig(@RequestParam String key, @RequestParam String value,
                                            @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.setConfig(key, value));
        supportFacade.audit(actor(user), "修改配置", key, "value=" + ("support.csOpenKey".equals(key) ? "******" : value));
        return resp;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestParam String username, @RequestParam String password) {
        return GatewaySupport.of(supportFacade.login(username, password));
    }

    @GetMapping("/admins")
    public ResponseEntity<Object> admins() {
        return GatewaySupport.of(supportFacade.listAdmins());
    }

    @PostMapping("/admins")
    public ResponseEntity<Object> saveAdmin(@RequestBody AdminUserDTO a,
                                            @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.saveAdmin(a));
        supportFacade.audit(actor(user), "维护管理员", a.getUsername(), "role=" + a.getRole());
        return resp;
    }

    @GetMapping("/audit")
    public ResponseEntity<Object> audit(@RequestParam(defaultValue = "100") int limit) {
        return GatewaySupport.of(supportFacade.listAudit(limit));
    }

    @GetMapping("/docs/pending")
    public ResponseEntity<Object> pendingDocs() {
        return GatewaySupport.of(supportFacade.listPendingDocs());
    }

    @PostMapping("/docs/{id}/review")
    public ResponseEntity<Object> reviewDoc(@PathVariable String id, @RequestParam boolean approve,
                                            @RequestParam(required = false) String reason,
                                            @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.reviewDoc(id, approve, reason));
        supportFacade.audit(actor(user), approve ? "证件通过" : "证件驳回", id, reason == null ? "" : reason);
        return resp;
    }

    @GetMapping("/tickets")
    public ResponseEntity<Object> tickets() {
        return GatewaySupport.of(supportFacade.listTickets());
    }

    @PostMapping("/tickets/{id}/status")
    public ResponseEntity<Object> ticketStatus(@PathVariable String id, @RequestParam String status,
                                               @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN", "SUPPORT")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.updateTicketStatus(id, status));
        supportFacade.audit(actor(user), "工单状态", id, status);
        return resp;
    }

    @GetMapping("/safety/events")
    public ResponseEntity<Object> events(@RequestParam(required = false) String orderId) {
        return GatewaySupport.of(supportFacade.listEvents(orderId));
    }

    @PostMapping("/safety/events/{id}/handle")
    public ResponseEntity<Object> handleEvent(@PathVariable String id,
                                              @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN", "SUPPORT")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.handleEvent(id));
        supportFacade.audit(actor(user), "安全事件处置", id, "HANDLED");
        return resp;
    }

    /** 乘客封禁/解封（ADMIN）。 */
    @PostMapping("/passengers/{id}/block")
    public ResponseEntity<Object> blockPassenger(@PathVariable String id, @RequestParam boolean blocked,
                                                 @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(passengerFacade.setBlocked(id, blocked));
        supportFacade.audit(actor(user), blocked ? "封禁乘客" : "解封乘客", id, "");
        return resp;
    }

    /** 司机封禁/解封（ADMIN，封禁强制下线）。 */
    @PostMapping("/drivers/{id}/block")
    public ResponseEntity<Object> blockDriver(@PathVariable String id, @RequestParam boolean blocked,
                                              @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN")) return deny();
        ResponseEntity<Object> resp = GatewaySupport.of(driverFacade.setBlocked(id, blocked));
        supportFacade.audit(actor(user), blocked ? "封禁司机" : "解封司机", id, "");
        return resp;
    }

    /** 退款审批流：申请（OPS/FINANCE/ADMIN）。 */
    @PostMapping("/refund-requests")
    public ResponseEntity<Object> createRefund(@RequestBody com.qoder.ride.facade.support.RefundRequestDTO r,
                                               @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN", "OPS", "FINANCE")) return deny();
        r.setRequestedBy(actor(user));
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.createRefundRequest(r));
        supportFacade.audit(actor(user), "退款申请", r.getOrderId(), "amount=" + r.getAmount());
        return resp;
    }

    @GetMapping("/refund-requests")
    public ResponseEntity<Object> listRefunds() {
        return GatewaySupport.of(supportFacade.listRefundRequests());
    }

    /** 退款审批（ADMIN/FINANCE）：批准则执行订单域退款并置 EXECUTED。 */
    @PostMapping("/refund-requests/{id}/review")
    public ResponseEntity<Object> reviewRefund(@PathVariable String id, @RequestParam boolean approve,
                                               @RequestHeader(value = "X-Admin-User", required = false) String user) {
        if (!allowed(user, "ADMIN", "FINANCE")) return deny();
        com.qoder.ride.facade.support.RefundRequestDTO r = supportFacade.listRefundRequests().orThrow().stream()
                .filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "退款申请不存在", "code", "A0404"));
        if (!"PENDING".equals(r.getStatus())) return ResponseEntity.status(409).body(Map.of("error", "申请已处理", "code", "A0409"));
        if (approve) {
            ResponseEntity<Object> exec = GatewaySupport.of(orderFacade.refund(r.getOrderId(), r.getReason() == null ? "审批退款" : r.getReason(), r.getAmount()));
            if (!exec.getStatusCode().is2xxSuccessful()) return exec;
        }
        ResponseEntity<Object> resp = GatewaySupport.of(supportFacade.reviewRefundRequest(id, approve, actor(user)));
        supportFacade.audit(actor(user), approve ? "退款批准并执行" : "退款驳回", id, r.getOrderId());
        return resp;
    }
}