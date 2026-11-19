package com.qoder.ride.support.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
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
import com.qoder.ride.facade.support.SupportFacade;
import com.qoder.ride.facade.support.TicketLinkDTO;
import com.qoder.ride.support.service.SupportService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
public class SupportFacadeImpl implements SupportFacade {

    private final SupportService service;

    @Override public Result<List<PoiDTO>> searchPoi(String keyword) { return FacadeSupport.execute(() -> service.searchPoi(keyword)); }
    @Override public Result<List<AddressDTO>> listAddresses(String userId) { return FacadeSupport.execute(() -> service.listAddresses(userId)); }
    @Override public Result<AddressDTO> saveAddress(AddressDTO a) { return FacadeSupport.execute(() -> service.saveAddress(a)); }
    @Override public Result<Void> deleteAddress(String id) { return FacadeSupport.execute(() -> { service.deleteAddress(id); return null; }); }
    @Override public Result<List<CouponTemplateDTO>> listTemplates() { return FacadeSupport.execute(service::listTemplates); }
    @Override public Result<CouponTemplateDTO> createTemplate(CouponTemplateDTO t) { return FacadeSupport.execute(() -> service.createTemplate(t)); }
    @Override public Result<List<CouponDTO>> listCoupons(String userId) { return FacadeSupport.execute(() -> service.listCoupons(userId)); }
    @Override public Result<List<CouponDTO>> listAllCoupons() { return FacadeSupport.execute(service::listAllCoupons); }
    @Override public Result<CouponDTO> issueCoupon(String templateId, String userId) { return FacadeSupport.execute(() -> service.issueCoupon(templateId, userId)); }
    @Override public Result<CouponDTO> redeemCoupon(String couponId, String orderId) { return FacadeSupport.execute(() -> service.redeemCoupon(couponId, orderId)); }
    @Override public Result<CouponDTO> bestCoupon(String userId, long amount) { return FacadeSupport.execute(() -> service.bestCoupon(userId, amount)); }
    @Override public Result<List<SafetyContactDTO>> listContacts(String userId) { return FacadeSupport.execute(() -> service.listContacts(userId)); }
    @Override public Result<SafetyContactDTO> saveContact(SafetyContactDTO c) { return FacadeSupport.execute(() -> service.saveContact(c)); }
    @Override public Result<Void> deleteContact(String id) { return FacadeSupport.execute(() -> { service.deleteContact(id); return null; }); }
    @Override public Result<SafetyEventDTO> raiseEvent(SafetyEventDTO e) { return FacadeSupport.execute(() -> service.raiseEvent(e)); }
    @Override public Result<List<SafetyEventDTO>> listEvents(String orderId) { return FacadeSupport.execute(() -> service.listEvents(orderId)); }
    @Override public Result<SafetyEventDTO> handleEvent(String id) { return FacadeSupport.execute(() -> service.handleEvent(id)); }
    @Override public Result<Map<String, String>> allConfig() { return FacadeSupport.execute(service::allConfig); }
    @Override public Result<Void> setConfig(String key, String value) { return FacadeSupport.execute(() -> { service.setConfig(key, value); return null; }); }
    @Override public Result<AdminUserDTO> login(String u, String p) { return FacadeSupport.execute(() -> service.login(u, p)); }
    @Override public Result<AdminUserDTO> adminByUsername(String u) { return FacadeSupport.execute(() -> service.adminByUsername(u)); }
    @Override public Result<List<AdminUserDTO>> listAdmins() { return FacadeSupport.execute(service::listAdmins); }
    @Override public Result<AdminUserDTO> saveAdmin(AdminUserDTO a) { return FacadeSupport.execute(() -> service.saveAdmin(a)); }
    @Override public Result<Void> audit(String admin, String action, String target, String detail) { return FacadeSupport.execute(() -> { service.audit(admin, action, target, detail); return null; }); }
    @Override public Result<List<AuditLogDTO>> listAudit(int limit) { return FacadeSupport.execute(() -> service.listAudit(limit)); }
    @Override public Result<InvoiceDTO> createInvoice(InvoiceDTO i) { return FacadeSupport.execute(() -> service.createInvoice(i)); }
    @Override public Result<List<InvoiceDTO>> listInvoices(String userId) { return FacadeSupport.execute(() -> service.listInvoices(userId)); }
    @Override public Result<MemberDTO> getMember(String userId) { return FacadeSupport.execute(() -> service.getMember(userId)); }
    @Override public Result<MemberDTO> addPoints(String userId, long points, long spent) { return FacadeSupport.execute(() -> service.addPoints(userId, points, spent)); }
    @Override public Result<ImMessageDTO> sendIm(ImMessageDTO m) { return FacadeSupport.execute(() -> service.sendIm(m)); }
    @Override public Result<List<ImMessageDTO>> listIm(String orderId) { return FacadeSupport.execute(() -> service.listIm(orderId)); }
    @Override public Result<NotificationDTO> notify(NotificationDTO n) { return FacadeSupport.execute(() -> service.notify(n)); }
    @Override public Result<List<NotificationDTO>> listNotifications(String userId) { return FacadeSupport.execute(() -> service.listNotifications(userId)); }
    @Override public Result<Void> markRead(String id) { return FacadeSupport.execute(() -> { service.markRead(id); return null; }); }
    @Override public Result<DriverDocDTO> submitDoc(DriverDocDTO d) { return FacadeSupport.execute(() -> service.submitDoc(d)); }
    @Override public Result<List<DriverDocDTO>> listDocs(String driverId) { return FacadeSupport.execute(() -> service.listDocs(driverId)); }
    @Override public Result<List<DriverDocDTO>> listPendingDocs() { return FacadeSupport.execute(service::listPendingDocs); }
    @Override public Result<DriverDocDTO> reviewDoc(String id, boolean approve, String reason) { return FacadeSupport.execute(() -> service.reviewDoc(id, approve, reason)); }
    @Override public Result<TicketLinkDTO> linkTicket(TicketLinkDTO t) { return FacadeSupport.execute(() -> service.linkTicket(t)); }
    @Override public Result<List<TicketLinkDTO>> listTickets() { return FacadeSupport.execute(service::listTickets); }
    @Override public Result<TicketLinkDTO> updateTicketStatus(String id, String status) { return FacadeSupport.execute(() -> service.updateTicketStatus(id, status)); }
    @Override public Result<com.qoder.ride.facade.support.RefundRequestDTO> createRefundRequest(com.qoder.ride.facade.support.RefundRequestDTO r) { return FacadeSupport.execute(() -> service.createRefundRequest(r)); }
    @Override public Result<List<com.qoder.ride.facade.support.RefundRequestDTO>> listRefundRequests() { return FacadeSupport.execute(service::listRefundRequests); }
    @Override public Result<com.qoder.ride.facade.support.RefundRequestDTO> reviewRefundRequest(String id, boolean approve, String approvedBy) { return FacadeSupport.execute(() -> service.reviewRefundRequest(id, approve, approvedBy)); }
}