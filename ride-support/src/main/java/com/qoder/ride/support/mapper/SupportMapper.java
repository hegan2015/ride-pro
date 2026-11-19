package com.qoder.ride.support.mapper;

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
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SupportMapper {

    long nextId();

    // ---- POI / 地址 ----
    @Select("SELECT * FROM poi WHERE name LIKE #{kw} OR alias LIKE #{kw} OR category LIKE #{kw} ORDER BY id")
    List<PoiDTO> searchPoi(@Param("kw") String kw);

    @Insert("INSERT INTO address(id,user_id,label,name,lat,lng,created_at) VALUES(#{id},#{userId},#{label},#{name},#{lat},#{lng},#{createdAt})")
    int insertAddress(AddressDTO a);

    @Update("UPDATE address SET label=#{label},name=#{name},lat=#{lat},lng=#{lng} WHERE id=#{id}")
    int updateAddress(AddressDTO a);

    @Select("SELECT * FROM address WHERE user_id=#{userId} ORDER BY created_at DESC, id")
    List<AddressDTO> selectAddressByUser(String userId);

    @Select("SELECT * FROM address WHERE id=#{id}")
    AddressDTO selectAddressById(String id);

    @Update("DELETE FROM address WHERE id=#{id}")
    int deleteAddress(String id);

    // ---- 优惠券 ----
    @Insert("INSERT INTO coupon_template(id,name,type,benefit,min_amount,valid_days,total,issued,status,created_at) VALUES(#{id},#{name},#{type},#{benefit},#{minAmount},#{validDays},#{total},#{issued},#{status},#{createdAt})")
    int insertTemplate(CouponTemplateDTO t);

    @Select("SELECT * FROM coupon_template ORDER BY created_at DESC, id")
    List<CouponTemplateDTO> selectTemplates();

    @Select("SELECT * FROM coupon_template WHERE id=#{id}")
    CouponTemplateDTO selectTemplateById(String id);

    @Update("UPDATE coupon_template SET issued=issued+1 WHERE id=#{id}")
    int incrIssued(String id);

    @Insert("INSERT INTO coupon(id,template_id,user_id,code,status,order_id,expire_at,created_at) VALUES(#{id},#{templateId},#{userId},#{code},#{status},#{orderId},#{expireAt},#{createdAt})")
    int insertCoupon(CouponDTO c);

    @Select("SELECT c.*, t.name, t.type, t.benefit, t.min_amount FROM coupon c JOIN coupon_template t ON c.template_id=t.id WHERE c.user_id=#{userId} ORDER BY c.created_at DESC, c.id")
    List<CouponDTO> selectCouponsByUser(String userId);

    @Select("SELECT c.*, t.name, t.type, t.benefit, t.min_amount FROM coupon c JOIN coupon_template t ON c.template_id=t.id ORDER BY c.created_at DESC, c.id")
    List<CouponDTO> selectAllCoupons();

    @Select("SELECT c.*, t.name, t.type, t.benefit, t.min_amount FROM coupon c JOIN coupon_template t ON c.template_id=t.id WHERE c.id=#{id}")
    CouponDTO selectCouponById(String id);

    @Update("UPDATE coupon SET status=#{status}, order_id=#{orderId} WHERE id=#{id}")
    int updateCouponStatus(@Param("id") String id, @Param("status") String status, @Param("orderId") String orderId);

    // ---- 安全 ----
    @Insert("INSERT INTO safety_contact(id,user_id,name,phone,relation,created_at) VALUES(#{id},#{userId},#{name},#{phone},#{relation},#{createdAt})")
    int insertContact(SafetyContactDTO c);

    @Select("SELECT * FROM safety_contact WHERE user_id=#{userId} ORDER BY created_at, id")
    List<SafetyContactDTO> selectContactsByUser(String userId);

    @Update("DELETE FROM safety_contact WHERE id=#{id}")
    int deleteContact(String id);

    @Insert("INSERT INTO safety_event(id,user_id,order_id,type,detail,status,created_at) VALUES(#{id},#{userId},#{orderId},#{type},#{detail},#{status},#{createdAt})")
    int insertEvent(SafetyEventDTO e);

    @Select("SELECT * FROM safety_event WHERE order_id=#{orderId} ORDER BY created_at DESC, id")
    List<SafetyEventDTO> selectEventsByOrder(String orderId);

    @Select("SELECT * FROM safety_event ORDER BY created_at DESC, id")
    List<SafetyEventDTO> selectAllEvents();

    @Select("SELECT * FROM safety_event WHERE id=#{id}")
    SafetyEventDTO selectEventById(String id);

    @Update("UPDATE safety_event SET status=#{status} WHERE id=#{id}")
    int updateEventStatus(@Param("id") String id, @Param("status") String status);

    // ---- 配置 ----
    @Select("SELECT cfg_key, cfg_value FROM support_config ORDER BY cfg_key")
    List<Map<String, Object>> selectConfig();

    int upsertConfig(@Param("key") String key, @Param("value") String value, @Param("updatedAt") long updatedAt);

    // ---- RBAC / 审计 ----
    @Insert("INSERT INTO admin_user(id,username,password_hash,role,status,created_at) VALUES(#{id},#{username},#{passwordHash},#{role},#{status},#{createdAt})")
    int insertAdmin(AdminUserDTO a);

    @Update("UPDATE admin_user SET role=#{role},status=#{status},password_hash=#{passwordHash} WHERE id=#{id}")
    int updateAdmin(AdminUserDTO a);

    @Select("SELECT * FROM admin_user ORDER BY created_at, id")
    List<AdminUserDTO> selectAdmins();

    @Select("SELECT * FROM admin_user WHERE username=#{username}")
    AdminUserDTO selectAdminByUsername(String username);

    @Insert("INSERT INTO audit_log(id,admin,action,target,detail,created_at) VALUES(#{id},#{admin},#{action},#{target},#{detail},#{createdAt})")
    int insertAudit(AuditLogDTO a);

    @Select("SELECT * FROM audit_log ORDER BY created_at DESC, id LIMIT #{limit}")
    List<AuditLogDTO> selectAudit(int limit);

    // ---- 发票 ----
    @Insert("INSERT INTO invoice(id,user_id,order_ids,title,tax_no,amount,status,created_at) VALUES(#{id},#{userId},#{orderIds},#{title},#{taxNo},#{amount},#{status},#{createdAt})")
    int insertInvoice(InvoiceDTO i);

    @Select("SELECT * FROM invoice WHERE user_id=#{userId} ORDER BY created_at DESC, id")
    List<InvoiceDTO> selectInvoicesByUser(String userId);

    // ---- 会员 ----
    @Select("SELECT * FROM member WHERE user_id=#{userId}")
    MemberDTO selectMember(String userId);

    @Insert("INSERT INTO member(user_id,points,level,total_spent,updated_at) VALUES(#{userId},#{points},#{level},#{totalSpent},#{updatedAt})")
    int insertMember(MemberDTO m);

    @Update("UPDATE member SET points=#{points},level=#{level},total_spent=#{totalSpent},updated_at=#{updatedAt} WHERE user_id=#{userId}")
    int updateMember(MemberDTO m);

    // ---- IM ----
    @Insert("INSERT INTO im_message(id,order_id,from_role,from_id,text,created_at) VALUES(#{id},#{orderId},#{fromRole},#{fromId},#{text},#{createdAt})")
    int insertIm(ImMessageDTO m);

    @Select("SELECT * FROM im_message WHERE order_id=#{orderId} ORDER BY created_at, id")
    List<ImMessageDTO> selectImByOrder(String orderId);

    // ---- 通知 ----
    @Insert("INSERT INTO notification(id,user_id,type,title,body,read_flag,created_at) VALUES(#{id},#{userId},#{type},#{title},#{body},#{readFlag},#{createdAt})")
    int insertNotification(NotificationDTO n);

    @Select("SELECT * FROM notification WHERE user_id=#{userId} ORDER BY created_at DESC, id")
    List<NotificationDTO> selectNotificationsByUser(String userId);

    @Update("UPDATE notification SET read_flag=TRUE WHERE id=#{id}")
    int markRead(String id);

    // ---- 司机证件 ----
    @Insert("INSERT INTO driver_doc(id,driver_id,type,doc_no,expiry,status,file_url,reason,created_at) VALUES(#{id},#{driverId},#{type},#{docNo},#{expiry},#{status},#{fileUrl},#{reason},#{createdAt})")
    int insertDoc(DriverDocDTO d);

    @Select("SELECT * FROM driver_doc WHERE driver_id=#{driverId} ORDER BY created_at DESC, id")
    List<DriverDocDTO> selectDocsByDriver(String driverId);

    @Select("SELECT * FROM driver_doc WHERE status='PENDING' ORDER BY created_at, id")
    List<DriverDocDTO> selectPendingDocs();

    @Select("SELECT * FROM driver_doc WHERE id=#{id}")
    DriverDocDTO selectDocById(String id);

    @Update("UPDATE driver_doc SET status=#{status}, reason=#{reason} WHERE id=#{id}")
    int updateDocStatus(@Param("id") String id, @Param("status") String status, @Param("reason") String reason);

    // ---- 工单 linkage ----
    @Insert("INSERT INTO ticket_link(id,order_id,user_id,cs_ticket_id,category,status,created_at) VALUES(#{id},#{orderId},#{userId},#{csTicketId},#{category},#{status},#{createdAt})")
    int insertTicket(TicketLinkDTO t);

    @Select("SELECT * FROM ticket_link ORDER BY created_at DESC, id")
    List<TicketLinkDTO> selectTickets();

    @Select("SELECT * FROM ticket_link WHERE id=#{id}")
    TicketLinkDTO selectTicketById(String id);

    @Update("UPDATE ticket_link SET status=#{status} WHERE id=#{id}")
    int updateTicketStatus(@Param("id") String id, @Param("status") String status);

    // ---- 退款审批 ----
    @Insert("INSERT INTO refund_request(id,order_id,amount,reason,status,requested_by,approved_by,created_at) VALUES(#{id},#{orderId},#{amount},#{reason},#{status},#{requestedBy},#{approvedBy},#{createdAt})")
    int insertRefund(com.qoder.ride.facade.support.RefundRequestDTO r);

    @Select("SELECT * FROM refund_request ORDER BY created_at DESC, id")
    List<com.qoder.ride.facade.support.RefundRequestDTO> selectRefunds();

    @Select("SELECT * FROM refund_request WHERE id=#{id}")
    com.qoder.ride.facade.support.RefundRequestDTO selectRefundById(String id);

    @Update("UPDATE refund_request SET status=#{status}, approved_by=#{approvedBy} WHERE id=#{id}")
    int updateRefundStatus(@Param("id") String id, @Param("status") String status, @Param("approvedBy") String approvedBy);
}