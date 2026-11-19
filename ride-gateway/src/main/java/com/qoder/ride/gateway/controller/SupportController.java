package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.support.AddressDTO;
import com.qoder.ride.facade.support.CouponDTO;
import com.qoder.ride.facade.support.DriverDocDTO;
import com.qoder.ride.facade.support.ImMessageDTO;
import com.qoder.ride.facade.support.InvoiceDTO;
import com.qoder.ride.facade.support.NotificationDTO;
import com.qoder.ride.facade.support.SafetyContactDTO;
import com.qoder.ride.facade.support.SafetyEventDTO;
import com.qoder.ride.facade.support.SupportFacade;
import com.qoder.ride.facade.support.TicketLinkDTO;
import com.qoder.ride.gateway.support.CsTicketClient;
import com.qoder.ride.gateway.web.GatewaySupport;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 司乘支撑接口：地址/POI、券、安全、IM、通知、会员、发票、证件、配置(脱敏)、投诉工单(转 cs-pro)。 */
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    @DubboReference
    private SupportFacade supportFacade;

    private final CsTicketClient csTicketClient;

    @GetMapping("/poi")
    public ResponseEntity<Object> poi(@RequestParam(required = false) String kw) {
        return GatewaySupport.of(supportFacade.searchPoi(kw));
    }

    @GetMapping("/addresses")
    public ResponseEntity<Object> addresses(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.listAddresses(userId));
    }

    @PostMapping("/addresses")
    public ResponseEntity<Object> saveAddress(@RequestBody AddressDTO address) {
        return GatewaySupport.of(supportFacade.saveAddress(address));
    }

    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<Object> deleteAddress(@PathVariable String id) {
        return GatewaySupport.of(supportFacade.deleteAddress(id));
    }

    @GetMapping("/coupons")
    public ResponseEntity<Object> coupons(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.listCoupons(userId));
    }

    /** 公开领券中心：仅 ACTIVE 模板（乘客端无需管理令牌）。 */
    @GetMapping("/templates")
    public ResponseEntity<Object> templates() {
        com.qoder.ride.common.result.Result<java.util.List<com.qoder.ride.facade.support.CouponTemplateDTO>> r = supportFacade.listTemplates();
        if (!r.isSuccess()) return GatewaySupport.of(r);
        return ResponseEntity.ok(r.getData().stream().filter(t -> "ACTIVE".equals(t.getStatus())).toList());
    }

    /** 行程支付后累加积分/消费（会员等级服务端判定）。 */
    @PostMapping("/member/points")
    public ResponseEntity<Object> points(@RequestParam String userId, @RequestParam long points, @RequestParam long spent) {
        return GatewaySupport.of(supportFacade.addPoints(userId, points, spent));
    }

    @PostMapping("/coupons/issue")
    public ResponseEntity<Object> issue(@RequestParam String templateId, @RequestParam String userId) {
        return GatewaySupport.of(supportFacade.issueCoupon(templateId, userId));
    }

    @GetMapping("/coupons/best")
    public ResponseEntity<Object> best(@RequestParam String userId, @RequestParam long amount) {
        return GatewaySupport.of(supportFacade.bestCoupon(userId, amount));
    }

    @PostMapping("/coupons/redeem")
    public ResponseEntity<Object> redeem(@RequestParam String couponId, @RequestParam String orderId) {
        return GatewaySupport.of(supportFacade.redeemCoupon(couponId, orderId));
    }

    @GetMapping("/contacts")
    public ResponseEntity<Object> contacts(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.listContacts(userId));
    }

    @PostMapping("/contacts")
    public ResponseEntity<Object> saveContact(@RequestBody SafetyContactDTO contact) {
        return GatewaySupport.of(supportFacade.saveContact(contact));
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<Object> deleteContact(@PathVariable String id) {
        return GatewaySupport.of(supportFacade.deleteContact(id));
    }

    @PostMapping("/safety/events")
    public ResponseEntity<Object> raiseEvent(@RequestBody SafetyEventDTO event) {
        return GatewaySupport.of(supportFacade.raiseEvent(event));
    }

    @GetMapping("/safety/events")
    public ResponseEntity<Object> events(@RequestParam(required = false) String orderId) {
        return GatewaySupport.of(supportFacade.listEvents(orderId));
    }

    @GetMapping("/im")
    public ResponseEntity<Object> im(@RequestParam String orderId) {
        return GatewaySupport.of(supportFacade.listIm(orderId));
    }

    @PostMapping("/im")
    public ResponseEntity<Object> sendIm(@RequestBody ImMessageDTO message) {
        return GatewaySupport.of(supportFacade.sendIm(message));
    }

    @GetMapping("/notifications")
    public ResponseEntity<Object> notifications(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.listNotifications(userId));
    }

    @PostMapping("/notifications/read/{id}")
    public ResponseEntity<Object> read(@PathVariable String id) {
        return GatewaySupport.of(supportFacade.markRead(id));
    }

    @GetMapping("/member")
    public ResponseEntity<Object> member(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.getMember(userId));
    }

    @PostMapping("/invoice")
    public ResponseEntity<Object> invoice(@RequestBody InvoiceDTO invoice) {
        return GatewaySupport.of(supportFacade.createInvoice(invoice));
    }

    @GetMapping("/invoices")
    public ResponseEntity<Object> invoices(@RequestParam String userId) {
        return GatewaySupport.of(supportFacade.listInvoices(userId));
    }

    @PostMapping("/docs")
    public ResponseEntity<Object> submitDoc(@RequestBody DriverDocDTO doc) {
        return GatewaySupport.of(supportFacade.submitDoc(doc));
    }

    @GetMapping("/docs")
    public ResponseEntity<Object> docs(@RequestParam String driverId) {
        return GatewaySupport.of(supportFacade.listDocs(driverId));
    }

    /** 公开配置（脱敏：不下发 cs open key）。 */
    @GetMapping("/config")
    public ResponseEntity<Object> config() {
        Map<String, String> cfg = new LinkedHashMap<>(supportFacade.allConfig().orThrow());
        cfg.remove("support.csOpenKey");
        return ResponseEntity.ok(cfg);
    }

    /** 投诉/客服工单：转发 cs-pro + 本地 linkage + 站内通知。 */
    @PostMapping("/tickets")
    public ResponseEntity<Object> ticket(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", ""));
        String orderId = String.valueOf(body.getOrDefault("orderId", ""));
        String category = String.valueOf(body.getOrDefault("category", "投诉"));
        String title = String.valueOf(body.getOrDefault("title", "客服工单"));
        String description = String.valueOf(body.getOrDefault("description", ""));
        // cs-pro 工单类别为限定枚举，做映射；本地 linkage 保留 ride 原始类别。
        String csCategory = switch (category) {
            case "费用异议" -> "计价";
            case "服务投诉", "安全问题" -> "投诉";
            case "失物招领" -> "其他";
            default -> "其他";
        };
        Map<String, Object> csBody = new LinkedHashMap<>();
        csBody.put("requestId", "RIDE-" + UUID.randomUUID());
        csBody.put("userId", userId);
        csBody.put("userName", body.getOrDefault("userName", userId));
        csBody.put("source", "ride-pro");
        csBody.put("category", csCategory);
        csBody.put("orderId", orderId);
        csBody.put("title", title);
        csBody.put("description", description);
        String csId = csTicketClient.createTicket(csBody);
        TicketLinkDTO link = new TicketLinkDTO();
        link.setOrderId(orderId);
        link.setUserId(userId);
        link.setCsTicketId(csId);
        link.setCategory(category);
        TicketLinkDTO saved = supportFacade.linkTicket(link).orThrow();
        NotificationDTO n = new NotificationDTO();
        n.setUserId(userId);
        n.setType("TICKET");
        n.setTitle("客服工单已受理");
        n.setBody(csId == null ? "工单 " + saved.getId() + " 已受理（客服通道离线，恢复后自动同步）" : "工单 " + saved.getId() + " 已提交客服，编号 " + csId);
        supportFacade.notify(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("link", saved);
        out.put("csTicketId", csId);
        return ResponseEntity.ok(out);
    }
}