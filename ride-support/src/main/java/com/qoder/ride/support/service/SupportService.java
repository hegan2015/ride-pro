package com.qoder.ride.support.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.facade.support.AddressDTO;
import com.qoder.ride.facade.support.AdminUserDTO;
import com.qoder.ride.facade.support.AuditLogDTO;
import com.qoder.ride.facade.support.CouponDTO;
import com.qoder.ride.facade.support.CouponTemplateDTO;
import com.qoder.ride.facade.support.DriverDocDTO;
import com.qoder.ride.facade.support.ImMessageDTO;
import com.qoder.ride.facade.support.InvoiceDTO;
import com.qoder.ride.facade.support.MemberDTO;
import com.qoder.ride.facade.support.NotificationDTO;
import com.qoder.ride.facade.support.PoiDTO;
import com.qoder.ride.facade.support.SafetyContactDTO;
import com.qoder.ride.facade.support.SafetyEventDTO;
import com.qoder.ride.facade.support.TicketLinkDTO;
import com.qoder.ride.support.mapper.SupportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    private final SupportMapper mapper;

    private String id(String prefix) {
        return prefix + mapper.nextId();
    }

    // ---- POI / 地址 ----
    public List<PoiDTO> searchPoi(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) {
            return mapper.searchPoi("%");
        }
        return mapper.searchPoi("%" + kw + "%");
    }

    public List<AddressDTO> listAddresses(String userId) {
        return mapper.selectAddressByUser(userId);
    }

    public AddressDTO saveAddress(AddressDTO a) {
        if (a.getName() == null || a.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "地址名称必填");
        }
        if (a.getId() == null || a.getId().isBlank()) {
            a.setId(id("AD"));
            a.setCreatedAt(System.currentTimeMillis());
            mapper.insertAddress(a);
        } else {
            mapper.updateAddress(a);
        }
        return mapper.selectAddressById(a.getId());
    }

    public void deleteAddress(String id) {
        mapper.deleteAddress(id);
    }

    // ---- 优惠券 ----
    public List<CouponTemplateDTO> listTemplates() {
        return mapper.selectTemplates();
    }

    public CouponTemplateDTO createTemplate(CouponTemplateDTO t) {
        if (t.getName() == null || t.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "券名称必填");
        }
        t.setId(id("CT"));
        t.setIssued(0);
        t.setStatus("ACTIVE");
        t.setCreatedAt(System.currentTimeMillis());
        if (t.getValidDays() == null) t.setValidDays(7);
        if (t.getTotal() == null) t.setTotal(0);
        if (t.getMinAmount() == null) t.setMinAmount(0L);
        mapper.insertTemplate(t);
        return mapper.selectTemplateById(t.getId());
    }

    public List<CouponDTO> listCoupons(String userId) {
        return mapper.selectCouponsByUser(userId);
    }

    public List<CouponDTO> listAllCoupons() {
        return mapper.selectAllCoupons();
    }

    public CouponDTO issueCoupon(String templateId, String userId) {
        CouponTemplateDTO t = mapper.selectTemplateById(templateId);
        if (t == null) throw new BizException(ResultCode.NOT_FOUND, "券模板不存在: " + templateId);
        if (!"ACTIVE".equals(t.getStatus())) throw new BizException(ResultCode.PARAM_ERROR, "券模板已停用");
        if (t.getTotal() > 0 && t.getIssued() >= t.getTotal()) throw new BizException(ResultCode.PARAM_ERROR, "券已发完");
        mapper.incrIssued(templateId);
        CouponDTO c = new CouponDTO();
        c.setId(id("CP"));
        c.setTemplateId(templateId);
        c.setUserId(userId);
        c.setCode("RIDE" + c.getId());
        c.setStatus("UNUSED");
        c.setExpireAt(System.currentTimeMillis() + t.getValidDays() * 86400000L);
        c.setCreatedAt(System.currentTimeMillis());
        mapper.insertCoupon(c);
        log.info("发券 couponId={} templateId={} userId={}", c.getId(), templateId, userId);
        return mapper.selectCouponById(c.getId());
    }

    public CouponDTO redeemCoupon(String couponId, String orderId) {
        CouponDTO c = mapper.selectCouponById(couponId);
        if (c == null) throw new BizException(ResultCode.NOT_FOUND, "券不存在: " + couponId);
        if (!"UNUSED".equals(c.getStatus())) throw new BizException(ResultCode.PARAM_ERROR, "券已使用或过期");
        if (c.getExpireAt() != null && c.getExpireAt() < System.currentTimeMillis()) {
            mapper.updateCouponStatus(couponId, "EXPIRED", null);
            throw new BizException(ResultCode.PARAM_ERROR, "券已过期");
        }
        mapper.updateCouponStatus(couponId, "USED", orderId);
        return mapper.selectCouponById(couponId);
    }

    public CouponDTO bestCoupon(String userId, long amount) {
        long now = System.currentTimeMillis();
        CouponDTO best = null;
        long bestSave = 0;
        for (CouponDTO c : mapper.selectCouponsByUser(userId)) {
            if (!"UNUSED".equals(c.getStatus())) continue;
            if (c.getExpireAt() != null && c.getExpireAt() < now) continue;
            if (c.getMinAmount() != null && amount < c.getMinAmount()) continue;
            long save = "DISCOUNT".equals(c.getType()) ? amount * (100 - c.getBenefit()) / 100 : c.getBenefit();
            if (save > bestSave) {
                bestSave = save;
                best = c;
            }
        }
        return best;
    }

    // ---- 安全 ----
    public List<SafetyContactDTO> listContacts(String userId) {
        return mapper.selectContactsByUser(userId);
    }

    public SafetyContactDTO saveContact(SafetyContactDTO c) {
        if (c.getName() == null || c.getName().isBlank()) throw new BizException(ResultCode.PARAM_ERROR, "联系人姓名必填");
        c.setId(id("SC"));
        c.setCreatedAt(System.currentTimeMillis());
        mapper.insertContact(c);
        return c;
    }

    public void deleteContact(String id) {
        mapper.deleteContact(id);
    }

    public SafetyEventDTO raiseEvent(SafetyEventDTO e) {
        e.setId(id("SE"));
        e.setStatus("OPEN");
        e.setCreatedAt(System.currentTimeMillis());
        mapper.insertEvent(e);
        log.warn("安全事件 type={} userId={} orderId={} detail={}", e.getType(), e.getUserId(), e.getOrderId(), e.getDetail());
        return e;
    }

    public List<SafetyEventDTO> listEvents(String orderId) {
        return orderId == null || orderId.isBlank() ? mapper.selectAllEvents() : mapper.selectEventsByOrder(orderId);
    }

    public SafetyEventDTO handleEvent(String id) {
        mapper.updateEventStatus(id, "HANDLED");
        return mapper.selectEventById(id);
    }

    // ---- 配置 ----
    public Map<String, String> allConfig() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectConfig()) {
            Object k = row.get("cfg_key");
            Object v = row.get("cfg_value");
            if (k != null) out.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
        }
        return out;
    }

    public void setConfig(String key, String value) {
        mapper.upsertConfig(key, value, System.currentTimeMillis());
        log.info("配置更新 key={}", key);
    }

    // ---- RBAC / 审计 ----
    public AdminUserDTO login(String username, String password) {
        AdminUserDTO a = mapper.selectAdminByUsername(username);
        if (a == null || !"ACTIVE".equals(a.getStatus())) throw new BizException(ResultCode.PARAM_ERROR, "账号不存在或已停用");
        if (!sha256(password).equals(a.getPasswordHash())) throw new BizException(ResultCode.PARAM_ERROR, "口令错误");
        a.setPasswordHash(null);
        return a;
    }

    public List<AdminUserDTO> listAdmins() {
        List<AdminUserDTO> list = mapper.selectAdmins();
        list.forEach(a -> a.setPasswordHash(null));
        return list;
    }

    public AdminUserDTO adminByUsername(String username) {
        AdminUserDTO a = mapper.selectAdminByUsername(username);
        if (a != null) a.setPasswordHash(null);
        return a;
    }

    public AdminUserDTO saveAdmin(AdminUserDTO a) {
        if (a.getUsername() == null || a.getUsername().isBlank()) throw new BizException(ResultCode.PARAM_ERROR, "用户名必填");
        if (a.getId() == null || a.getId().isBlank()) {
            a.setId(id("AU"));
            a.setCreatedAt(System.currentTimeMillis());
            a.setStatus("ACTIVE");
            a.setPasswordHash(sha256(a.getPasswordHash() == null ? "admin123" : a.getPasswordHash()));
            mapper.insertAdmin(a);
        } else {
            if (a.getPasswordHash() != null && !a.getPasswordHash().isBlank()) {
                a.setPasswordHash(sha256(a.getPasswordHash()));
            } else {
                AdminUserDTO old = mapper.selectAdminByUsername(a.getUsername());
                a.setPasswordHash(old == null ? null : old.getPasswordHash());
            }
            mapper.updateAdmin(a);
        }
        a.setPasswordHash(null);
        return a;
    }

    public void audit(String admin, String action, String target, String detail) {
        AuditLogDTO a = new AuditLogDTO();
        a.setId(id("LG"));
        a.setAdmin(admin);
        a.setAction(action);
        a.setTarget(target);
        a.setDetail(detail);
        a.setCreatedAt(System.currentTimeMillis());
        mapper.insertAudit(a);
    }

    public List<AuditLogDTO> listAudit(int limit) {
        return mapper.selectAudit(Math.max(1, Math.min(limit, 500)));
    }

    // ---- 发票 ----
    public InvoiceDTO createInvoice(InvoiceDTO i) {
        i.setId(id("IV"));
        i.setStatus("ISSUED");
        i.setCreatedAt(System.currentTimeMillis());
        mapper.insertInvoice(i);
        return i;
    }

    public List<InvoiceDTO> listInvoices(String userId) {
        return mapper.selectInvoicesByUser(userId);
    }

    // ---- 会员 ----
    public MemberDTO getMember(String userId) {
        MemberDTO m = mapper.selectMember(userId);
        if (m == null) {
            m = new MemberDTO();
            m.setUserId(userId);
            m.setPoints(0L);
            m.setLevel("普通会员");
            m.setTotalSpent(0L);
            m.setUpdatedAt(System.currentTimeMillis());
            mapper.insertMember(m);
        }
        return m;
    }

    public MemberDTO addPoints(String userId, long points, long spent) {
        MemberDTO m = getMember(userId);
        m.setPoints(m.getPoints() + points);
        m.setTotalSpent(m.getTotalSpent() + spent);
        m.setLevel(levelOf(m.getTotalSpent()));
        m.setUpdatedAt(System.currentTimeMillis());
        mapper.updateMember(m);
        return m;
    }

    private String levelOf(long spent) {
        if (spent >= 2000000) return "钻石会员";
        if (spent >= 500000) return "黄金会员";
        if (spent >= 100000) return "白银会员";
        return "普通会员";
    }

    // ---- IM ----
    public ImMessageDTO sendIm(ImMessageDTO m) {
        if (m.getText() == null || m.getText().isBlank()) throw new BizException(ResultCode.PARAM_ERROR, "消息内容必填");
        m.setId(id("IM"));
        m.setCreatedAt(System.currentTimeMillis());
        mapper.insertIm(m);
        return m;
    }

    public List<ImMessageDTO> listIm(String orderId) {
        return mapper.selectImByOrder(orderId);
    }

    // ---- 通知 ----
    public NotificationDTO notify(NotificationDTO n) {
        n.setId(id("NT"));
        n.setReadFlag(false);
        n.setCreatedAt(System.currentTimeMillis());
        mapper.insertNotification(n);
        return n;
    }

    public List<NotificationDTO> listNotifications(String userId) {
        return mapper.selectNotificationsByUser(userId);
    }

    public void markRead(String id) {
        mapper.markRead(id);
    }

    // ---- 司机证件 ----
    public DriverDocDTO submitDoc(DriverDocDTO d) {
        d.setId(id("DC"));
        d.setStatus("PENDING");
        d.setCreatedAt(System.currentTimeMillis());
        mapper.insertDoc(d);
        return d;
    }

    public List<DriverDocDTO> listDocs(String driverId) {
        return mapper.selectDocsByDriver(driverId);
    }

    public List<DriverDocDTO> listPendingDocs() {
        return mapper.selectPendingDocs();
    }

    public DriverDocDTO reviewDoc(String id, boolean approve, String reason) {
        mapper.updateDocStatus(id, approve ? "APPROVED" : "REJECTED", reason);
        return mapper.selectDocById(id);
    }

    // ---- 工单 linkage ----
    public TicketLinkDTO linkTicket(TicketLinkDTO t) {
        t.setId(id("TK"));
        t.setStatus("OPEN");
        t.setCreatedAt(System.currentTimeMillis());
        mapper.insertTicket(t);
        return t;
    }

    public List<TicketLinkDTO> listTickets() {
        return mapper.selectTickets();
    }

    public TicketLinkDTO updateTicketStatus(String id, String status) {
        mapper.updateTicketStatus(id, status);
        return mapper.selectTicketById(id);
    }

    // ---- 退款审批 ----
    public com.qoder.ride.facade.support.RefundRequestDTO createRefundRequest(com.qoder.ride.facade.support.RefundRequestDTO r) {
        if (r.getOrderId() == null || r.getOrderId().isBlank()) throw new BizException(ResultCode.PARAM_ERROR, "订单 ID 必填");
        r.setId(id("RR"));
        r.setStatus("PENDING");
        r.setCreatedAt(System.currentTimeMillis());
        mapper.insertRefund(r);
        return r;
    }

    public List<com.qoder.ride.facade.support.RefundRequestDTO> listRefundRequests() {
        return mapper.selectRefunds();
    }

    public com.qoder.ride.facade.support.RefundRequestDTO reviewRefundRequest(String id, boolean approve, String approvedBy) {
        mapper.updateRefundStatus(id, approve ? "EXECUTED" : "REJECTED", approvedBy);
        return mapper.selectRefundById(id);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "哈希失败");
        }
    }
}