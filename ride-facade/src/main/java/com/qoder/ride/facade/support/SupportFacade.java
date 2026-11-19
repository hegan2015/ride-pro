package com.qoder.ride.facade.support;

import com.qoder.ride.common.result.Result;

import java.util.List;
import java.util.Map;

/** 支撑域：地址/POI、优惠券、安全、配置、RBAC/审计、发票、会员、IM、通知、司机证件、工单 linkage。 */
public interface SupportFacade {

    // ---- 地址 / POI ----
    Result<List<PoiDTO>> searchPoi(String keyword);

    Result<List<AddressDTO>> listAddresses(String userId);

    Result<AddressDTO> saveAddress(AddressDTO address);

    Result<Void> deleteAddress(String id);

    // ---- 优惠券 ----
    Result<List<CouponTemplateDTO>> listTemplates();

    Result<CouponTemplateDTO> createTemplate(CouponTemplateDTO template);

    Result<List<CouponDTO>> listCoupons(String userId);

    Result<List<CouponDTO>> listAllCoupons();

    Result<CouponDTO> issueCoupon(String templateId, String userId);

    Result<CouponDTO> redeemCoupon(String couponId, String orderId);

    Result<CouponDTO> bestCoupon(String userId, long amount);

    // ---- 安全 ----
    Result<List<SafetyContactDTO>> listContacts(String userId);

    Result<SafetyContactDTO> saveContact(SafetyContactDTO contact);

    Result<Void> deleteContact(String id);

    Result<SafetyEventDTO> raiseEvent(SafetyEventDTO event);

    Result<List<SafetyEventDTO>> listEvents(String orderId);

    Result<SafetyEventDTO> handleEvent(String id);

    // ---- 配置 ----
    Result<Map<String, String>> allConfig();

    Result<Void> setConfig(String key, String value);

    // ---- RBAC / 审计 ----
    Result<AdminUserDTO> login(String username, String password);

    Result<AdminUserDTO> adminByUsername(String username);

    Result<List<AdminUserDTO>> listAdmins();

    Result<AdminUserDTO> saveAdmin(AdminUserDTO admin);

    Result<Void> audit(String admin, String action, String target, String detail);

    Result<List<AuditLogDTO>> listAudit(int limit);

    // ---- 发票 ----
    Result<InvoiceDTO> createInvoice(InvoiceDTO invoice);

    Result<List<InvoiceDTO>> listInvoices(String userId);

    // ---- 会员 ----
    Result<MemberDTO> getMember(String userId);

    Result<MemberDTO> addPoints(String userId, long points, long spent);

    // ---- IM ----
    Result<ImMessageDTO> sendIm(ImMessageDTO message);

    Result<List<ImMessageDTO>> listIm(String orderId);

    // ---- 通知 ----
    Result<NotificationDTO> notify(NotificationDTO notification);

    Result<List<NotificationDTO>> listNotifications(String userId);

    Result<Void> markRead(String id);

    // ---- 司机证件 ----
    Result<DriverDocDTO> submitDoc(DriverDocDTO doc);

    Result<List<DriverDocDTO>> listDocs(String driverId);

    Result<List<DriverDocDTO>> listPendingDocs();

    Result<DriverDocDTO> reviewDoc(String id, boolean approve, String reason);

    // ---- 工单 linkage（cs-pro） ----
    Result<TicketLinkDTO> linkTicket(TicketLinkDTO link);

    Result<List<TicketLinkDTO>> listTickets();

    Result<TicketLinkDTO> updateTicketStatus(String id, String status);

    // ---- 退款审批流 ----
    Result<RefundRequestDTO> createRefundRequest(RefundRequestDTO request);

    Result<List<RefundRequestDTO>> listRefundRequests();

    Result<RefundRequestDTO> reviewRefundRequest(String id, boolean approve, String approvedBy);
}